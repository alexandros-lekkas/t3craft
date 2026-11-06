#!/bin/bash
# Starts the local test world (if it isn't already up), opens Minecraft straight into it,
# and stops the world again when the game quits. Used by "T3 Craft.app".
#
# Optional profile (T3CRAFT_PROFILE, default scripts/launcher.local; not committed), a shell file with:
#   T3CRAFT_USERNAME=Steve            # offline player name (default Player); keeps your op and inventory
#   T3CRAFT_SERVER_DIR=run-server     # which local world; another directory is another world
#   T3CRAFT_TITLE="T3 Craft"          # name in notifications
# A second world gets its own app: scripts/install-macos-app.sh --name "My World" --profile <file>.
set -u
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="${T3CRAFT_PROFILE:-$REPO/scripts/launcher.local}"
# shellcheck disable=SC1090
[ -f "$PROFILE" ] && . "$PROFILE"
USERNAME="${T3CRAFT_USERNAME:-Player}"
SERVER_DIR="${T3CRAFT_SERVER_DIR:-run-server}"
TITLE="${T3CRAFT_TITLE:-T3 Craft}"
LOGS="${T3CRAFT_LOG_DIR:-$HOME/Library/Logs/T3Craft}"
mkdir -p "$LOGS"
cd "$REPO"
PORT="$(sed -n 's/^server-port=//p' "$SERVER_DIR/server.properties" 2>/dev/null)"
PORT="${PORT:-25565}"

notify() {
  osascript - "$1" "$TITLE" >/dev/null 2>&1 <<'APPLESCRIPT'
on run argv
  display notification (item 1 of argv) with title (item 2 of argv)
end run
APPLESCRIPT
}

if [ -z "${JAVA_HOME:-}" ] && ! JAVA_HOME="$(/usr/libexec/java_home -v 25+ 2>/dev/null)"; then
  osascript -e 'display alert "T3 Craft needs Java 25 or newer" message "Install a JDK (for example Temurin 25) and open T3 Craft again."' >/dev/null
  exit 1
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  notify "JAVA_HOME does not point to a JDK. Set it to Java 25 or newer."
  exit 1
fi
java_major="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if ! [[ "$java_major" =~ ^[0-9]+$ ]] || [ "$java_major" -lt 25 ]; then
  notify "T3 Craft needs Java 25 or newer. Check JAVA_HOME."
  exit 1
fi
export JAVA_HOME

launch_lock="${TMPDIR:-/tmp}/t3craft-launcher-$UID.lock"
if ! mkdir "$launch_lock" 2>/dev/null; then
  notify "A launcher is already running. If it crashed, remove $launch_lock and retry."
  exit 1
fi
trap 'rmdir "$launch_lock"' EXIT

if pgrep -f "java.*dli.env=client" >/dev/null; then
  notify "Minecraft is already running."
  exit 0
fi

# Optional SSH tunnels (scripts/tunnels.local, not committed): "local-port ssh-host remote-host:port".
# Useful when a remote T3 server only answers on its own loopback.
if [ -f "$REPO/scripts/tunnels.local" ]; then
  while read -r port host target; do
    case "$port" in ''|\#*) continue ;; esac
    if ! lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      ssh -f -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
        -L "$port:$target" "$host" >> "$LOGS/tunnels.log" 2>&1 || notify "Couldn't reach $host; its threads won't load."
    fi
  done < "$REPO/scripts/tunnels.local"
fi

server_job_pid=""
owned_server_pid=""
server_input_dir=""
server_marker=""

owns_server() {
  [ -n "$owned_server_pid" ] &&
    ps -p "$owned_server_pid" -o command= 2>/dev/null | grep -Fq -- "-Dt3craft.launcherId=$server_marker"
}

owns_job() {
  [ -n "$server_job_pid" ] &&
    ps -p "$server_job_pid" -o command= 2>/dev/null | grep -Fq -- "$server_marker"
}

cleanup() {
  [ -n "$server_input_dir" ] || return 0
  if [ -z "$server_job_pid" ]; then
    rm -f "$server_input_dir/stdin"
    rmdir "$server_input_dir"
    return 0
  fi
  # Only this runServer invocation reads this pipe. `stop` saves its world before exiting.
  printf 'stop\n' >&3
  for _ in $(seq 1 20); do
    if ! owns_job && ! owns_server; then break; fi
    sleep 1
  done
  owns_server && kill -TERM "$owned_server_pid" 2>/dev/null
  owns_job && kill -TERM "$server_job_pid" 2>/dev/null
  for _ in $(seq 1 5); do
    if ! owns_job && ! owns_server; then break; fi
    sleep 1
  done
  owns_server && kill -KILL "$owned_server_pid" 2>/dev/null
  owns_job && kill -KILL "$server_job_pid" 2>/dev/null
  wait "$server_job_pid" 2>/dev/null || true
  exec 3>&-
  rm -f "$server_input_dir/stdin"
  rmdir "$server_input_dir"
}
trap 'cleanup; rmdir "$launch_lock"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  notify "Starting your world…"
  server_input_dir="$(mktemp -d "${TMPDIR:-/tmp}/t3craft-launch.XXXXXX")" || exit 1
  server_marker="$(basename "$server_input_dir")"
  mkfifo "$server_input_dir/stdin" || exit 1
  exec 3<>"$server_input_dir/stdin"
  nohup ./gradlew runServer -PserverDir="$SERVER_DIR" -PlauncherId="$server_marker" --args=nogui <&3 > "$LOGS/server.log" 2>&1 &
  server_job_pid=$!
  for _ in $(seq 1 90); do
    for candidate in $(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null); do
      owned_server_pid="$candidate"
      owns_server && break
      owned_server_pid=""
    done
    [ -n "$owned_server_pid" ] && break
    owns_job || break
    sleep 2
  done
  if ! owns_server; then
    notify "The world did not start. See $LOGS/server.log"
    exit 1
  fi
fi

# Blocks until the game window is closed.
./gradlew runClient -Pjoin="localhost:$PORT" -Pusername="$USERNAME" > "$LOGS/client.log" 2>&1
exit $?
