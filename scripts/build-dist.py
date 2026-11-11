#!/usr/bin/env python3
"""Build everything a release publishes into build/dist/.

  t3craft-<version>.jar      the mod
  t3craft-<version>.mrpack   Modrinth-format pack (Modrinth App, Prism Launcher, ...)
  install.sh / install.ps1   one-step installers for the official Minecraft Launcher
  SHA256SUMS.txt

The pack is pinned in modpack/pack.json. `--refresh` re-reads every pinned version from the Modrinth API
and the Fabric meta server and rewrites the hashes; a normal build never touches the network.
"""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import sys
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / 'modpack/pack.json'
REPO_URL = 'https://github.com/alexandros-lekkas/t3craft'
AGENT = {'User-Agent': 't3craft-pack-builder'}


def properties():
    lines = (ROOT / 'gradle.properties').read_text().splitlines()
    return dict(line.split('=', 1) for line in lines if '=' in line and not line.startswith('#'))


def load_pack():
    return json.loads(PACK.read_text())


def fabric_profile_path(pack):
    return ROOT / 'modpack' / f"fabric-loader-{pack['fabric_loader']}-{pack['minecraft']}.json"


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=30) as response:
        return response.read()


def refresh():
    """Re-pin file names, URLs, sizes and hashes for the versions named in pack.json."""
    pack = load_pack()
    query = urllib.parse.urlencode({'game_versions': json.dumps([pack['minecraft']]), 'loaders': '["fabric"]'})
    for mod in pack['mods']:
        versions = json.loads(fetch(f"https://api.modrinth.com/v2/project/{mod['slug']}/version?{query}"))
        match = [v for v in versions if v['version_number'] == mod['version']]
        if not match:
            sys.exit(f"{mod['slug']} {mod['version']} has no Fabric build for Minecraft {pack['minecraft']} on Modrinth")
        file = next((f for f in match[0]['files'] if f['primary']), match[0]['files'][0])
        mod.update(filename=file['filename'], url=file['url'], size=file['size'],
                   sha1=file['hashes']['sha1'], sha512=file['hashes']['sha512'])
        data = fetch(file['url'])
        assert hashlib.sha512(data).hexdigest() == mod['sha512'] and len(data) == mod['size'], mod['slug']
        print(f"pinned {mod['slug']} {mod['version']} ({file['filename']}, {file['size']} bytes, download verified)")
    profile = fetch(f"https://meta.fabricmc.net/v2/versions/loader/{pack['minecraft']}/{pack['fabric_loader']}/profile/json")
    json.loads(profile)
    fabric_profile_path(pack).write_bytes(profile)
    PACK.write_text(json.dumps(pack, indent=2) + '\n')


def mrpack_index(pack, version):
    return {
        'formatVersion': 1,
        'game': 'minecraft',
        'versionId': version,
        'name': pack['name'],
        'summary': pack['summary'],
        'files': [{
            'path': 'mods/' + mod['filename'],
            'hashes': {'sha1': mod['sha1'], 'sha512': mod['sha512']},
            'env': {'client': 'required' if mod['required'] else 'optional', 'server': 'unsupported'},
            'downloads': [mod['url']],
            'fileSize': mod['size'],
        } for mod in pack['mods']],
        'dependencies': {'minecraft': pack['minecraft'], 'fabric-loader': pack['fabric_loader']},
    }


def write_mrpack(path, pack, version, jar):
    """Deterministic zip: fixed timestamps and order, so the same inputs give the same bytes."""
    entries = [('modrinth.index.json', (json.dumps(mrpack_index(pack, version), indent=2) + '\n').encode()),
               (f'overrides/mods/{jar.name}', jar.read_bytes())]
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)


def render(template, pack, version, jar_name, jar_sha256, fabric_profile, icon_png, powershell):
    """Stamp an installer template (scripts/install.sh or install.ps1) with the pinned pack."""
    profile = json.dumps(json.loads(fabric_profile), indent=2)
    assert "T3CRAFT_FABRIC_PROFILE" not in profile and "\n'@" not in profile
    for mod in pack['mods']:
        for field in ('filename', 'url', 'sha512', 'replaces'):
            assert not set(mod[field]) & set("|'\"\n\\ $`"), (mod['slug'], field)
    if powershell:
        mods = '\n'.join("  @{{ File = '{filename}'; Url = '{url}'; Sha512 = '{sha512}'; Replaces = '{replaces}'; Required = ${required} }}".format(
            **{**mod, 'required': str(mod['required']).lower()}) for mod in pack['mods'])
    else:
        mods = '\n'.join('{filename}|{url}|{sha512}|{replaces}|{required}'.format(
            **{**mod, 'required': 'required' if mod['required'] else 'extra'}) for mod in pack['mods'])
    values = {
        'VERSION': version, 'JAR': jar_name, 'JAR_SHA256': jar_sha256, 'MINECRAFT': pack['minecraft'],
        'LOADER': pack['fabric_loader'], 'NAME': pack['name'], 'MODS': mods, 'FABRIC_PROFILE': profile,
        'ICON': 'data:image/png;base64,' + base64.b64encode(icon_png).decode(),
    }
    for key, value in values.items():
        template = template.replace('@' + key + '@', value)
    return template


def build():
    props, pack = properties(), load_pack()
    version = props['version']
    jar = ROOT / 'build/libs' / f't3craft-{version}.jar'
    if not jar.is_file():
        sys.exit(f'Missing {jar.relative_to(ROOT)}; run ./gradlew build first')
    dist = ROOT / 'build/dist'
    shutil.rmtree(dist, ignore_errors=True)
    dist.mkdir(parents=True)
    shutil.copy2(jar, dist / jar.name)
    write_mrpack(dist / f't3craft-{version}.mrpack', pack, version, jar)
    jar_sha256 = hashlib.sha256(jar.read_bytes()).hexdigest()
    icon = (ROOT / 'src/main/resources/assets/t3craft/icon.png').read_bytes()
    for name in ('install.sh', 'install.ps1'):
        text = render((ROOT / 'scripts' / name).read_text(), pack, version, jar.name, jar_sha256,
                      fabric_profile_path(pack).read_text(), icon, name.endswith('.ps1'))
        # Windows PowerShell 5.1 reads a script without a BOM as ANSI; keep both installers ASCII.
        text.encode('ascii')
        (dist / name).write_bytes(text.encode())
    names = sorted(p.name for p in dist.iterdir())
    sums = ''.join(f"{hashlib.sha256((dist / n).read_bytes()).hexdigest()}  {n}\n" for n in names)
    (dist / 'SHA256SUMS.txt').write_text(sums)
    print('Built build/dist: ' + ', '.join(names + ['SHA256SUMS.txt']))


if __name__ == '__main__':
    if sys.argv[1:] == ['--refresh']:
        refresh()
    elif sys.argv[1:]:
        sys.exit(__doc__)
    else:
        build()
