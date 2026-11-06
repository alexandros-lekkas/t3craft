#!/usr/bin/env python3
"""Check local Markdown/HTML links, images and Markdown heading anchors."""
from pathlib import Path
from urllib.parse import unquote, urlsplit
import html
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent

def clean(text):
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"^`{3,}.*?^`{3,}[^\n]*", "", text, flags=re.M | re.S)
    return re.sub(r"`[^`\n]*`", "", text)

def anchors(path):
    text = path.read_text()
    result = set(re.findall(r'(?:id|name)=["\x27]([^"\x27]+)', text))
    counts = {}
    for title in re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", text, re.M):
        title = re.sub(r"<[^>]+>", "", title)
        title = re.sub(r"\[([^]]+)\]\([^)]*\)", r"\1", title)
        slug = re.sub(r"[^\w\- ]", "", html.unescape(title).lower()).replace(" ", "-")
        n = counts.get(slug, 0)
        counts[slug] = n + 1
        result.add(slug + (f"-{n}" if n else ""))
    return result

files = subprocess.check_output(
    ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
    cwd=ROOT, text=True).split("\0")
errors = []
checked = 0
for name in sorted(set(files)):
    path = ROOT / name
    if path.suffix not in (".md", ".html") or not path.is_file():
        continue
    text = clean(path.read_text())
    links = re.findall(r'!?\[[^]\n]*\]\(\s*<?([^\s)>]+)>?(?:\s+["\x27][^\n]*?["\x27])?\s*\)', text)
    links += re.findall(r'(?:href|src)=["\x27]([^"\x27]+)', text)
    links += re.findall(r'^\s*\[[^]]+\]:\s*<?([^\s>]+)', text, re.M)
    for link in links:
        url = urlsplit(html.unescape(link))
        if url.scheme or url.netloc or link.startswith("/"):
            continue
        target = (path.parent / unquote(url.path)).resolve() if url.path else path
        checked += 1
        if not target.exists():
            errors.append(f"{name}: missing {link}")
        elif url.fragment and target.suffix == ".md" and unquote(url.fragment) not in anchors(target):
            errors.append(f"{name}: missing anchor {link}")
for error in errors:
    print(error)
print(f"Checked {checked} local links; {len(errors)} errors.")
sys.exit(bool(errors))
