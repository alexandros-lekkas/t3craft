#!/usr/bin/env bash
# T3 Craft installer for the official Minecraft Launcher (macOS and Linux).
#
#   curl -fsSL https://github.com/alexandros-lekkas/t3craft/releases/latest/download/install.sh | bash
#
# It adds one installation called "T3 Craft" with its own game folder, and touches nothing else:
#   <launcher>/versions/fabric-loader-<loader>-<minecraft>/   the Fabric profile (the launcher fetches Fabric itself)
#   <launcher>-t3craft/mods/                                  T3 Craft, Fabric API and the light Vanilla+ mods
#   <launcher>/launcher_profiles.json                         one added entry; a backup is kept beside it
# Run it again to update. Options: --minimal (only T3 Craft and Fabric API), or T3CRAFT_MINIMAL=1.
#
# This file is a template: scripts/build-dist.py stamps the @...@ values and writes build/dist/install.sh.
set -euo pipefail

main() {
  local version='@VERSION@' jar='@JAR@' jar_sha256='@JAR_SHA256@' minecraft='@MINECRAFT@' loader='@LOADER@'
  local name='@NAME@' icon='@ICON@' key='t3craft-modpack'
  local mods='@MODS@'
  if [ "${version#@}" != "$version" ]; then
    fail "this is the installer template. Use install.sh from a release, or run scripts/build-dist.py."
  fi
  local minimal="${T3CRAFT_MINIMAL:-}"
  while [ $# -gt 0 ]; do
    case "$1" in
      --minimal) minimal=1 ;;
      *) fail "unknown option: $1" ;;
    esac
    shift
  done

  local launcher json_tool
  case "$(uname -s)" in
    Darwin) launcher="$HOME/Library/Application Support/minecraft"; json_tool=osascript ;;
    Linux)
      launcher="$HOME/.minecraft"; json_tool=python3
      if [ ! -d "$launcher" ] && [ -d "$HOME/.var/app/com.mojang.Minecraft/.minecraft" ]; then
        launcher="$HOME/.var/app/com.mojang.Minecraft/.minecraft"
      fi ;;
    *) fail "this installer is for macOS and Linux. On Windows use install.ps1 (see docs/INSTALL.md)." ;;
  esac
  launcher="${T3CRAFT_MINECRAFT_DIR:-$launcher}"
  json_tool="${T3CRAFT_JSON_TOOL:-$json_tool}"
  local profiles="$launcher/launcher_profiles.json"
  local jar_url="${T3CRAFT_JAR_URL:-https://github.com/alexandros-lekkas/t3craft/releases/download/v$version/$jar}"

  command -v curl >/dev/null || fail "curl is needed."
  command -v "$json_tool" >/dev/null || fail "$json_tool is needed."
  if [ ! -f "$profiles" ]; then
    fail "the Minecraft Launcher was not found at $launcher.
Install it from https://www.minecraft.net/download, open it once, sign in, quit it, and run this again."
  fi
  if [ -z "${T3CRAFT_IGNORE_RUNNING_LAUNCHER:-}" ] &&
     pgrep -f 'Minecraft\.app/Contents/MacOS/launcher|minecraft-launcher' >/dev/null 2>&1; then
    fail "the Minecraft Launcher is open, and it would overwrite the new installation when it closes.
Quit the launcher and run this again."
  fi

  # Reading the profile list first also proves the file parses before anything is written.
  local game
  game="$(profile_json "$json_tool" gamedir "$profiles" "$key")" || fail "could not read $profiles; nothing was changed."
  game="${T3CRAFT_GAME_DIR:-${game:-$launcher-t3craft}}"

  # Download and verify everything before touching the launcher or the game folder.
  local staging
  staging="$(mktemp -d "${TMPDIR:-/tmp}/t3craft-install.XXXXXX")"
  # shellcheck disable=SC2064
  trap "rm -rf '$staging'" EXIT
  echo "Installing T3 Craft $version for Minecraft $minecraft (Fabric Loader $loader)"
  download "$jar_url" "$staging/$jar"
  verify 256 "$jar_sha256" "$staging/$jar"
  local file url sha512 replaces kind keep=""
  while IFS='|' read -r file url sha512 replaces kind; do
    [ -n "$file" ] || continue
    if [ -n "$minimal" ] && [ "$kind" != required ]; then continue; fi
    if [ -n "${T3CRAFT_MODRINTH_CDN:-}" ]; then url="$T3CRAFT_MODRINTH_CDN${url#https://cdn.modrinth.com}"; fi
    download "$url" "$staging/$file"
    verify 512 "$sha512" "$staging/$file"
    keep="$keep$file|$replaces
"
  done <<EOF
$mods
EOF

  local id="fabric-loader-$loader-$minecraft"
  mkdir -p "$launcher/versions/$id" "$game/mods"
  cat > "$staging/$id.json" <<'T3CRAFT_FABRIC_PROFILE'
@FABRIC_PROFILE@
T3CRAFT_FABRIC_PROFILE
  mv "$staging/$id.json" "$launcher/versions/$id/$id.json"

  # Replace only the files this installer manages; mods you added yourself stay.
  rm -f "$game/mods"/t3craft-*.jar
  mv "$staging/$jar" "$game/mods/$jar"
  while IFS='|' read -r file replaces; do
    [ -n "$file" ] || continue
    # shellcheck disable=SC2086
    (cd "$game/mods" && rm -f $replaces)
    mv "$staging/$file" "$game/mods/$file"
  done <<EOF
$keep
EOF

  cp "$profiles" "$profiles.t3craft-backup"
  profile_json "$json_tool" apply "$profiles" "$key" "$name" "$id" "$game" "$icon" "$(date -u +%Y-%m-%dT%H:%M:%S.000Z)" >/dev/null ||
    fail "could not update $profiles. The previous file is at $profiles.t3craft-backup."

  cat <<EOF

T3 Craft $version is installed.
  Game folder: $game

  1. Open T3 Code.
  2. Open the Minecraft Launcher, choose "$name" next to the Play button, and press Play.
  3. Click "T3 World" on the title screen.
EOF
}

fail() {
  echo "T3 Craft installer: $1" >&2
  exit 1
}

download() {
  curl --fail --silent --show-error --location --retry 3 --output "$2" "$1" || fail "could not download $1"
}

verify() {
  local actual
  if command -v shasum >/dev/null; then actual="$(shasum -a "$1" "$3")"; else actual="$("sha${1}sum" "$3")"; fi
  if [ "${actual%% *}" != "$2" ]; then
    fail "$(basename "$3") does not match its pinned checksum; nothing was installed."
  fi
}

# profile_json <tool> gamedir <file> <key>                                  prints the installation's game folder, if any
# profile_json <tool> apply <file> <key> <name> <version> <game> <icon> <now>   adds or updates that one installation
profile_json() {
  local tool="$1"
  shift
  if [ "$tool" = osascript ]; then
    osascript -l JavaScript - "$@" <<'JXA'
ObjC.import('Foundation');
function run(argv) {
  const [mode, path, key, name, version, game, icon, now] = argv;
  const text = $.NSString.stringWithContentsOfFileEncodingError(path, $.NSUTF8StringEncoding, null);
  if (text.isNil()) throw new Error('cannot read ' + path);
  const data = JSON.parse(text.js);
  if (data === null || typeof data !== 'object' || Array.isArray(data)) throw new Error('unexpected launcher file');
  if (data.profiles === undefined) data.profiles = {};
  if (typeof data.profiles !== 'object' || Array.isArray(data.profiles)) throw new Error('unexpected launcher file');
  let profile = data.profiles[key];
  if (mode === 'gamedir') return (profile && profile.gameDir) || '';
  if (!profile) profile = data.profiles[key] = {created: now, icon: icon, name: name, type: 'custom'};
  profile.gameDir = game;
  profile.lastVersionId = version;
  profile.lastUsed = now;
  const out = $(JSON.stringify(data, null, 2) + '\n');
  if (!out.writeToFileAtomicallyEncodingError(path, true, $.NSUTF8StringEncoding, null)) throw new Error('cannot write ' + path);
  return '';
}
JXA
  else
    "$tool" - "$@" <<'PY'
import json, os, sys
mode, path, key = sys.argv[1:4]
with open(path, encoding='utf-8') as file:
    data = json.load(file)
if not isinstance(data, dict) or not isinstance(data.setdefault('profiles', {}), dict):
    sys.exit('unexpected launcher file')
profile = data['profiles'].get(key)
if mode == 'gamedir':
    print((profile or {}).get('gameDir') or '')
    sys.exit()
name, version, game, icon, now = sys.argv[4:9]
if not profile:
    profile = data['profiles'][key] = {'created': now, 'icon': icon, 'name': name, 'type': 'custom'}
profile.update(gameDir=game, lastVersionId=version, lastUsed=now)
with open(path + '.t3craft-tmp', 'w', encoding='utf-8') as file:
    json.dump(data, file, indent=2, ensure_ascii=False)
    file.write('\n')
os.replace(path + '.t3craft-tmp', path)
PY
  fi
}

main "$@"
