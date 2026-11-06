#!/usr/bin/env python3
"""Check the distributable jar, not loose build resources."""
import json
from pathlib import Path
from zipfile import ZipFile

properties = dict(line.split('=', 1) for line in Path('gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
jar = Path('build/libs') / ('t3craft-' + properties['version'] + '.jar')
with ZipFile(jar) as archive:
    names = set(archive.namelist())
    metadata = json.loads(archive.read('fabric.mod.json'))
    assert metadata['id'] == 't3craft'
    assert metadata['version'] == properties['version']
    assert metadata['depends']['java'] == '>=25'
    assert metadata['depends']['fabric-api'] == '>=0.161.0'
    assert metadata['icon'] in names
    for entries in metadata['entrypoints'].values():
        for entry in entries:
            assert entry.replace('.', '/') + '.class' in names, entry
    assert not any(name.startswith('dev/maxwellyoung/t3craft/') and name.endswith('Checks.class') for name in names)
    assert 'dev/maxwellyoung/t3craft/Smoke.class' not in names
    classes = [archive.read(name) for name in names if name.startswith('dev/maxwellyoung/t3craft/') and name.endswith('.class')]
    assert classes and all(int.from_bytes(data[6:8], 'big') == 69 for data in classes), 'mod classes must target Java 25'
print('PASS jar metadata, entrypoints, icon, Java 25 bytecode and smoke exclusion')
