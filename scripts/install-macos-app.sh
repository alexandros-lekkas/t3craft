#!/bin/bash
# Build a complete bundle before replacing an installed launcher.
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FOLDER=/Applications
NAME="T3 Craft"
PROFILE=""
ICON="$REPO/scripts/AppIcon.icns"
while [ $# -gt 0 ]; do
  case "$1" in
    --name|--profile|--icon)
      [ $# -ge 2 ] || { echo "Missing value for $1" >&2; exit 1; }
      case "$1" in
        --name) NAME="$2" ;;
        --profile) PROFILE="$2" ;;
        --icon) ICON="$2" ;;
      esac
      shift 2 ;;
    --*) echo "Unknown option: $1" >&2; exit 1 ;;
    *) FOLDER="$1"; shift ;;
  esac
done
case "$NAME" in ''|.|..|*/*) echo 'Invalid app name' >&2; exit 1 ;; esac
[ -f "$ICON" ] || { echo "Missing icon: $ICON" >&2; exit 1; }
[ -x "$REPO/scripts/launch-macos.sh" ] || { echo 'Missing launcher' >&2; exit 1; }
if [ -n "$PROFILE" ]; then
  [ -f "$PROFILE" ] || { echo "Missing profile: $PROFILE" >&2; exit 1; }
  PROFILE="$(cd "$(dirname "$PROFILE")" && pwd)/$(basename "$PROFILE")"
fi
command -v python3 >/dev/null
mkdir -p "$FOLDER"
DEST="$FOLDER/$NAME.app"
STAGING="$(mktemp -d "$FOLDER/.t3craft-install.XXXXXX")"
BUNDLE="$STAGING/$NAME.app"
cleanup() {
  if [ -e "$STAGING/previous.app" ] && [ ! -e "$DEST" ]; then mv "$STAGING/previous.app" "$DEST"; fi
  rm -rf "$STAGING"
}
trap cleanup EXIT
mkdir -p "$BUNDLE/Contents/MacOS" "$BUNDLE/Contents/Resources"
{
  printf '#!/bin/bash\n'
  if [ -n "$PROFILE" ]; then printf 'export T3CRAFT_PROFILE=%q\n' "$PROFILE"; fi
  printf 'exec %q\n' "$REPO/scripts/launch-macos.sh"
} > "$BUNDLE/Contents/MacOS/T3Craft"
chmod +x "$BUNDLE/Contents/MacOS/T3Craft"
python3 - "$NAME" "$BUNDLE" "$REPO" <<'PY'
import hashlib, plistlib, sys
from pathlib import Path
name, bundle, repo = sys.argv[1:]
version = next(line.split('=', 1)[1] for line in (Path(repo) / 'gradle.properties').read_text().splitlines() if line.startswith('version='))
metadata = dict(CFBundleName=name, CFBundleDisplayName=name,
    CFBundleIdentifier='com.alexandroslekkas.t3craft.launcher.' + hashlib.sha256(name.encode()).hexdigest()[:16],
    CFBundleExecutable='T3Craft', CFBundleIconFile='AppIcon', CFBundlePackageType='APPL',
    CFBundleShortVersionString=version, CFBundleVersion=version, LSMinimumSystemVersion='12.0', LSUIElement=True)
with (Path(bundle) / 'Contents/Info.plist').open('wb') as out:
    plistlib.dump(metadata, out)
PY
cp "$ICON" "$BUNDLE/Contents/Resources/AppIcon.icns"
if ! codesign --force --deep --sign - "$BUNDLE"; then
  echo 'Warning: ad-hoc signing failed; macOS may require approval to open the app.' >&2
fi
if [ -e "$DEST" ]; then mv "$DEST" "$STAGING/previous.app"; fi
mv "$BUNDLE" "$DEST"
touch "$DEST"
echo "Installed $DEST"
