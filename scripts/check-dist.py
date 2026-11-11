#!/usr/bin/env python3
"""Check the release artifacts in build/dist and run the installers against a throwaway launcher folder.

The installer runs are hermetic: a stand-in pack is stamped into the real templates and served from
127.0.0.1, so no real launcher folder, Modrinth or GitHub is touched. `--installers-only` skips the
build/dist checks (for a machine that has not built the jar, such as the Windows CI job).
"""
import functools
import hashlib
import http.server
import importlib.util
import json
import os
import re
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import threading
import zipfile

ROOT = Path(__file__).resolve().parent.parent
sys.dont_write_bytecode = True  # importing the builder must not leave scripts/__pycache__ behind
spec = importlib.util.spec_from_file_location('build_dist', ROOT / 'scripts/build-dist.py')
build_dist = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_dist)
KEY = 't3craft-modpack'


def sha(algorithm, data):
    return hashlib.new(algorithm, data).hexdigest()


def numbers(version):
    return [int(part) for part in version.split('+')[0].split('.')]


def check_artifacts():
    props, pack = build_dist.properties(), build_dist.load_pack()
    version, dist = props['version'], ROOT / 'build/dist'
    jar = dist / f't3craft-{version}.jar'
    expected = {jar.name, f't3craft-{version}.mrpack', 'install.sh', 'install.ps1', 'SHA256SUMS.txt'}
    assert {p.name for p in dist.iterdir()} == expected, sorted(p.name for p in dist.iterdir())
    sums = dict(reversed(line.split('  ', 1)) for line in (dist / 'SHA256SUMS.txt').read_text().splitlines())
    assert set(sums) == expected - {'SHA256SUMS.txt'}
    for name, digest in sums.items():
        assert sha('sha256', (dist / name).read_bytes()) == digest, name

    # The pack must satisfy what the jar itself asks for.
    with zipfile.ZipFile(jar) as archive:
        depends = json.loads(archive.read('fabric.mod.json'))['depends']
    assert depends['minecraft'] == '~' + pack['minecraft']
    assert numbers(pack['fabric_loader']) >= numbers(depends['fabricloader'].lstrip('>='))
    api = next(mod for mod in pack['mods'] if mod['slug'] == 'fabric-api')
    assert api['required'] and numbers(api['version']) >= numbers(depends['fabric-api'].lstrip('>='))
    assert pack['minecraft'] == props['minecraft_version']
    fabric = json.loads(build_dist.fabric_profile_path(pack).read_text())
    assert fabric['id'] == f"fabric-loader-{pack['fabric_loader']}-{pack['minecraft']}"
    assert fabric['inheritsFrom'] == pack['minecraft'] and fabric['libraries'] and fabric['mainClass']
    for mod in pack['mods']:
        assert mod['url'].startswith('https://cdn.modrinth.com/data/'), mod['slug']
        assert len(mod['sha512']) == 128 and len(mod['sha1']) == 40 and mod['size'] > 0, mod['slug']
        assert mod['url'].endswith('/' + mod['filename'].replace('+', '%2B')), mod['slug']

    # Modrinth pack format 1: https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack
    with zipfile.ZipFile(dist / f't3craft-{version}.mrpack') as archive:
        assert archive.testzip() is None
        assert set(archive.namelist()) == {'modrinth.index.json', f'overrides/mods/{jar.name}'}
        assert archive.read(f'overrides/mods/{jar.name}') == jar.read_bytes()
        index = json.loads(archive.read('modrinth.index.json'))
    assert index['formatVersion'] == 1 and index['game'] == 'minecraft' and index['versionId'] == version
    assert index['name'] == pack['name'] and index['summary']
    assert index['dependencies'] == {'minecraft': pack['minecraft'], 'fabric-loader': pack['fabric_loader']}
    assert len(index['files']) == len(pack['mods'])
    for file, mod in zip(index['files'], pack['mods']):
        assert file['path'] == 'mods/' + mod['filename'] and file['downloads'] == [mod['url']]
        assert file['hashes'] == {'sha1': mod['sha1'], 'sha512': mod['sha512']} and file['fileSize'] == mod['size']
        assert file['env']['client'] == ('required' if mod['required'] else 'optional')

    for name in ('install.sh', 'install.ps1'):
        text = (dist / name).read_text(encoding='ascii')
        assert '@VERSION@' not in text and '@MODS@' not in text and '@FABRIC_PROFILE@' not in text, name
        assert sums[jar.name] in text and f"'{version}'" in text, name
        for mod in pack['mods']:
            assert mod['sha512'] in text and mod['url'] in text, (name, mod['slug'])
    subprocess.run(['bash', '-n', str(dist / 'install.sh')], check=True)
    print('PASS release artifacts: jar, .mrpack index and override, stamped installers, SHA256SUMS')


def check_installers():
    variants = []
    if platform.system() != 'Windows':
        if platform.system() == 'Darwin':
            variants.append(('install.sh (osascript)', 'install.sh', ['bash'], {}))
        variants.append(('install.sh (python3)', 'install.sh', ['bash'], {'T3CRAFT_JSON_TOOL': sys.executable}))
    for shell in ('powershell', 'pwsh'):
        if shutil.which(shell):
            variants.append((f'install.ps1 ({shell})', 'install.ps1',
                             [shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File'], {}))
    assert variants
    with tempfile.TemporaryDirectory(prefix='t3craft-dist-check-') as folder:
        root = Path(folder).resolve()
        web = root / 'web'
        (web / 'jar').mkdir(parents=True)
        (web / 'cdn/data/test').mkdir(parents=True)
        files = {'fake-api-1.0+26.3.jar': b'api', 'fake-extra-2.0.jar': b'extra'}
        pack = {'name': 'T3 Craft', 'minecraft': '26.3', 'fabric_loader': '0.20.1', 'mods': [
            dict(slug=name.rsplit('-', 1)[0], filename=name, required=name.startswith('fake-api'),
                 replaces=name.rsplit('-', 1)[0] + '-*.jar', sha512=sha('sha512', data),
                 url='https://cdn.modrinth.com/data/test/' + name.replace('+', '%2B'))
            for name, data in files.items()]}
        for name, data in files.items():
            (web / 'cdn/data/test' / name).write_bytes(data)
        jar_name, jar_data = 't3craft-9.9.9.jar', b'mod'
        (web / 'jar' / jar_name).write_bytes(jar_data)
        fabric_profile = build_dist.fabric_profile_path(build_dist.load_pack()).read_text()
        icon = (ROOT / 'src/main/resources/assets/t3craft/icon.png').read_bytes()
        scripts = root / 'scripts'; scripts.mkdir()
        for name in ('install.sh', 'install.ps1'):
            text = build_dist.render((ROOT / 'scripts' / name).read_text(), pack, '9.9.9', jar_name,
                                     sha('sha256', jar_data), fabric_profile, icon, name.endswith('.ps1'))
            (scripts / name).write_bytes(text.encode('ascii'))
        tools = root / 'bin'; tools.mkdir()
        pgrep = tools / 'pgrep'

        def launcher_running(value):
            pgrep.write_text('#!/bin/sh\nexit %d\n' % (0 if value else 1)); pgrep.chmod(0o755)

        class Quiet(http.server.SimpleHTTPRequestHandler):
            def log_message(self, *args):
                pass

        handler = functools.partial(Quiet, directory=str(web))
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f'http://127.0.0.1:{server.server_address[1]}'
        try:
            for index, (label, script, command, extra) in enumerate(variants):
                powershell = script.endswith('.ps1')
                home = root / f'home-{index}'
                launcher = home / 'minecraft dir'
                game = home / 'minecraft dir-t3craft'
                env = {**os.environ, 'HOME': str(home), 'PATH': str(tools) + os.pathsep + os.environ['PATH'],
                       'T3CRAFT_MINECRAFT_DIR': str(launcher), 'T3CRAFT_JAR_URL': f'{base}/jar/{jar_name}',
                       'T3CRAFT_MODRINTH_CDN': f'{base}/cdn', **extra}
                for name in ('T3CRAFT_GAME_DIR', 'T3CRAFT_MINIMAL', 'T3CRAFT_IGNORE_RUNNING_LAUNCHER'):
                    env.pop(name, None)
                if powershell:
                    env['T3CRAFT_IGNORE_RUNNING_LAUNCHER'] = '1'
                    # A PowerShell 7 parent (the CI step shell) leaves its PSModulePath behind, and Windows
                    # PowerShell 5.1 then cannot load its own built-in modules (Get-FileHash, ConvertFrom-Json).
                    for name in [key for key in env if key.upper() == 'PSMODULEPATH']:  # Windows upper-cases keys
                        env.pop(name)
                launcher_running(False)

                def install(*args, **overrides):
                    return subprocess.run([*command, str(scripts / script), *args], env={**env, **overrides},
                                          capture_output=True, text=True, timeout=120)

                def said(result, phrase):
                    # PowerShell 7 colours its errors and wraps them mid-word at the console width.
                    plain = re.sub(r'\x1b\[[0-9;]*m', '', result.stderr + result.stdout)
                    return re.sub(r'[\s|]', '', phrase) in re.sub(r'[\s|]', '', plain)

                def tree():
                    # PowerShell 7 keeps its own startup cache and module folder under HOME on Linux and macOS.
                    own = ('.cache', '.local', '.config')
                    return sorted(p.relative_to(home).as_posix() for p in home.rglob('*')
                                  if not (powershell and p.relative_to(home).parts[0] in own))

                # No launcher: say so and create nothing.
                home.mkdir()
                result = install()
                assert result.returncode != 0 and said(result, 'Minecraft Launcher was not found'), (label, result)
                assert tree() == [], (label, tree())

                original = {'profiles': {
                    'a' * 32: {'created': '1970-01-02T00:00:00.000Z', 'icon': 'Grass', 'lastUsed': '2026-11-01T10:00:00.000Z',
                               'lastVersionId': 'latest-release', 'name': '', 'type': 'latest-release'},
                    'mine': {'created': '2026-01-02T00:00:00.000Z', 'gameDir': str(home / 'my world'), 'icon': 'Furnace',
                             'javaArgs': '-Xmx4G', 'lastUsed': '2026-11-02T10:00:00.000Z',
                             'lastVersionId': 'fabric-loader-0.19.5-26.3', 'name': 'Möds & "quotes"', 'type': 'custom'}},
                    'settings': {'enableSnapshots': False, 'profileSorting': 'ByLastPlayed'}, 'version': 6}
                launcher.mkdir()
                profiles = launcher / 'launcher_profiles.json'
                original_text = json.dumps(original, indent=2, ensure_ascii=False)
                profiles.write_text(original_text, encoding='utf-8')
                (launcher / 'saves/My World').mkdir(parents=True)
                (launcher / 'saves/My World/level.dat').write_text('world')
                (launcher / 'mods').mkdir()
                (launcher / 'mods/other-mod.jar').write_text('other')
                before = tree()

                # A corrupt profile list, an open launcher or a bad download must leave everything as it was.
                profiles.write_text('{ not json', encoding='utf-8')
                result = install()
                assert result.returncode != 0 and said(result, 'nothing was changed'), (label, result)
                profiles.write_text(original_text, encoding='utf-8')
                if not powershell:
                    launcher_running(True)
                    result = install()
                    assert result.returncode != 0 and 'Quit the launcher' in result.stderr, (label, result)
                    launcher_running(False)
                (web / 'cdn/data/test/fake-extra-2.0.jar').write_bytes(b'tampered')
                result = install()
                (web / 'cdn/data/test/fake-extra-2.0.jar').write_bytes(files['fake-extra-2.0.jar'])
                assert result.returncode != 0 and said(result, 'pinned checksum'), (label, result)
                assert tree() == before and profiles.read_text(encoding='utf-8') == original_text, (label, tree())

                # A clean install: the Fabric profile, the mods and exactly one new launcher entry.
                result = install()
                assert result.returncode == 0, (label, result)
                assert 'T3 Craft 9.9.9 is installed' in result.stdout, (label, result)
                version_id = 'fabric-loader-0.20.1-26.3'
                written = json.loads((launcher / 'versions' / version_id / (version_id + '.json')).read_text())
                assert written == json.loads(fabric_profile), label
                assert sorted(p.name for p in (game / 'mods').iterdir()) == sorted([jar_name, *files]), label
                assert (game / 'mods/fake-api-1.0+26.3.jar').read_bytes() == b'api'
                assert (profiles.parent / (profiles.name + '.t3craft-backup')).read_text(encoding='utf-8') == original_text
                after = json.loads(profiles.read_text(encoding='utf-8'))
                entry = after['profiles'].pop(KEY)
                if not powershell:  # PowerShell 7 re-reads timestamps as dates and may drop the milliseconds.
                    assert after == original, (label, after)
                assert set(after['profiles']) == set(original['profiles']) and after['settings'] == original['settings']
                assert after['profiles']['mine']['name'] == 'Möds & "quotes"' and after['profiles']['mine']['javaArgs'] == '-Xmx4G'
                assert after['profiles']['a' * 32]['name'] == '' and after['version'] == 6
                assert entry['name'] == 'T3 Craft' and entry['type'] == 'custom' and entry['lastVersionId'] == version_id, entry
                assert Path(entry['gameDir']) == game and entry['icon'].startswith('data:image/png;base64,'), entry
                assert entry['created'] == entry['lastUsed'] and entry['created'].endswith('Z'), entry
                assert (launcher / 'saves/My World/level.dat').read_text() == 'world'
                assert [p.name for p in (launcher / 'mods').iterdir()] == ['other-mod.jar']
                assert not [p for p in tree() if 't3craft-tmp' in p], label

                # An update keeps the player's own mods, settings and game folder, and replaces older managed jars.
                moved = home / 'elsewhere'
                game.rename(moved)
                for name in ('t3craft-0.0.1.jar', 'fake-api-0.5.jar', 'my-own-mod.jar'):
                    (moved / 'mods' / name).write_text('old')
                (moved / 'mods/fake-extra-2.0.jar').unlink()
                edited = json.loads(profiles.read_text(encoding='utf-8'))
                edited['profiles'][KEY].update(gameDir=str(moved), javaArgs='-Xmx6G', name='My office',
                                                lastVersionId='fabric-loader-0.19.5-26.3')
                profiles.write_text(json.dumps(edited, indent=2), encoding='utf-8')
                result = install('--minimal') if script == 'install.sh' and index == 0 else install(T3CRAFT_MINIMAL='1')
                assert result.returncode == 0, (label, result)
                assert sorted(p.name for p in (moved / 'mods').iterdir()) == ['fake-api-1.0+26.3.jar', 'my-own-mod.jar', jar_name], label
                assert not game.exists(), label
                entry = json.loads(profiles.read_text(encoding='utf-8'))['profiles'][KEY]
                assert Path(entry['gameDir']) == moved and entry['javaArgs'] == '-Xmx6G' and entry['name'] == 'My office', entry
                assert entry['lastVersionId'] == version_id, entry
                print(f'PASS {label}: refuses unsafe states untouched, installs one T3 Craft entry, updates in place')
        finally:
            server.shutdown()


if __name__ == '__main__':
    if '--installers-only' not in sys.argv[1:]:
        check_artifacts()
    check_installers()
