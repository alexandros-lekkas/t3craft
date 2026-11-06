#!/usr/bin/env python3
"""Regression checks using the real catalog and an isolated asset tree."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

GENERATOR = Path(__file__).with_name('generate.py')


class GeneratedInputsTest(unittest.TestCase):
    def test_directory_glob_is_stable_and_removed_font_becomes_dormant(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            font = root / 'fonts/inter/example.ttf'
            font.parent.mkdir(parents=True)
            font.write_bytes(b'fixture font')

            def generate(*args, expected=0):
                result = subprocess.run([sys.executable, '-I', str(GENERATOR),
                                         '--repo', str(root), *args],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)

            generate()
            first = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
            generate()
            self.assertEqual(first, {p.relative_to(root): p.read_bytes()
                                     for p in root.rglob('*') if p.is_file()})
            generate('--check')
            attribution = font.with_name('ATTRIBUTION.md')
            self.assertIn('example.ttf', attribution.read_text())
            self.assertNotIn('`ATTRIBUTION.md`', attribution.read_text())
            font.unlink()
            generate('--check', expected=2)
            generate()
            self.assertFalse(attribution.exists())
            data = json.loads((root / 'src/main/resources/assets/t3craft/credits.json').read_text())
            self.assertNotIn('font-inter', json.dumps(data))
            self.assertNotIn('## Inter', (root / 'CREDITS.md').read_text())
            generate('--check')


if __name__ == '__main__':
    unittest.main()
