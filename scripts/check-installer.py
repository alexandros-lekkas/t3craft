#!/usr/bin/env python3
"""Check replacement, validation and plist encoding in a disposable install folder."""
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile

script = Path(__file__).with_name('install-macos-app.sh').resolve()
with tempfile.TemporaryDirectory(prefix='t3craft-install-check-') as directory:
    root = Path(directory)
    tools = root / 'bin'; tools.mkdir()
    signer = tools / 'codesign'; signer.write_text('#!/bin/sh\nexit 1\n'); signer.chmod(0o755)
    env = {**os.environ, 'PATH': str(tools) + ':' + os.environ['PATH']}
    name = 'A & B "Office"'
    destination = root / (name + '.app'); destination.mkdir()
    marker = destination / 'existing'; marker.write_text('keep')
    def install(*args):
        return subprocess.run(['bash', str(script), str(root), '--name', name, *args], env=env, capture_output=True, text=True)
    assert install('--icon', str(root / 'missing.icns')).returncode != 0
    assert marker.read_text() == 'keep', 'invalid inputs removed the installed app'
    assert install('--profile', str(root / 'missing.profile')).returncode != 0
    assert marker.exists()
    assert install('--name', '../escape').returncode != 0
    assert marker.exists()
    result = install()
    assert result.returncode == 0, result.stderr
    assert 'signing failed' in result.stderr
    assert not marker.exists()
    with (destination / 'Contents/Info.plist').open('rb') as file:
        metadata = plistlib.load(file)
    assert metadata['CFBundleName'] == name
    assert metadata['CFBundleIdentifier'].startswith('com.alexandroslekkas.t3craft.')
    assert metadata['CFBundleVersion'] == metadata['CFBundleShortVersionString']
    assert not list(root.glob('.t3craft-install.*'))
print('PASS installer validates before replacement, escapes plist names and reports signing failures')
