#!/usr/bin/env bash
# Records the launch video from the real game, without touching the mouse, the keyboard or the screen:
# starts the office fixture on a free port, runs a muted dev client whose DemoRecorder scripts the scene and
# pipes its own framebuffer to ffmpeg (build/demo/raw/seg-*.mp4), then cuts the deliverables with edit.sh.
#
#   scripts/demo/record.sh            # record and edit, one command
#   scripts/demo/edit.sh              # re-cut from the last recording
#
# Needs JDK 25 (JAVA_HOME) and ffmpeg. T3CRAFT_DEMO_MCP_PORT moves the dev client's MCP port (default 25611).
# If a staged beat does not happen (the agent is not where the script expects, a card does not open, the ping
# never shows) the recorder stops and this script prints the reason and exits 1; nothing is cut from a broken take.
set -euo pipefail
cd "$(dirname "$0")/../.."
root=$PWD
out=$root/build/demo
raw=$out/raw
ffmpeg=${FFMPEG:-$(command -v ffmpeg)}
mcp_port=${T3CRAFT_DEMO_MCP_PORT:-25611}
rm -rf "$raw"; mkdir -p "$raw" run

# The dev client's own run directory: muted, never paused in the background, no first-run screens.
touch run/options.txt
for line in soundCategory_master:0.0 pauseOnLostFocus:false onboardAccessibility:false skipMultiplayerWarning:true tutorialStep:none \
	narrator:0 guiScale:5 maxFps:60 enableVsync:false renderDistance:16 simulationDistance:8 fov:0.0 bobView:true autoJump:true \
	'inactivityFpsLimit:"minimized"'; do
	key=${line%%:*}
	grep -v "^$key:" run/options.txt > run/options.txt.new || true
	echo "$line" >> run/options.txt.new
	mv run/options.txt.new run/options.txt
done

rm -f "$out/fixture.port"
python3 scripts/office/fixture.py --demo --port 0 --port-file "$out/fixture.port" --config "$out/office-fixture.json" --mcp-port "$mcp_port" > "$out/fixture.log" 2>&1 &
fixture=$!
trap 'kill $fixture 2>/dev/null || true' EXIT
for _ in $(seq 50); do [ -s "$out/fixture.port" ] && break; sleep 0.2; done
[ -s "$out/fixture.port" ] || { echo "The office fixture did not start; see $out/fixture.log" >&2; exit 1; }
port=$(cat "$out/fixture.port")
echo "Office fixture on $port; recording to $raw (about four minutes)"

# SDL (Minecraft's window backend) reads these: show the window without activating it, and stay a background app.
SDL_WINDOW_ACTIVATE_WHEN_SHOWN=0 SDL_MAC_BACKGROUND_APP=${T3CRAFT_DEMO_BACKGROUND_APP:-1} \
	./gradlew runClient -Pdemo="$raw" -Pconfig="$out/office-fixture.json" -PfixturePort="$port" -PmcpPort="$mcp_port" \
	-PdemoFfmpeg="$ffmpeg" -Pwidth=960 -Pheight=540 > "$out/client.log" 2>&1 || true
result=$(grep -o "DEMO RESULT .*" "$out/client.log" | tail -1 || true)
if [ -z "$result" ]; then
	echo "The recording did not finish: the client stopped without a result. See $out/client.log" >&2
	exit 1
fi
if [ "${result#DEMO RESULT PASS}" = "$result" ]; then
	echo "The recording FAILED and nothing was cut: ${result#DEMO RESULT }" >&2
	echo "Last steps:" >&2
	grep -o "DEMO \(step\|mark\|standing\|survival\|opening\) .*" "$out/client.log" | tail -6 >&2 || true
	echo "Full log: $out/client.log" >&2
	exit 1
fi
echo "$result"
if [ "${T3CRAFT_DEMO_NO_EDIT:-}" = 1 ]; then
	echo "Shots recorded: $raw/seg-*.mp4 (marks in $raw/marks.txt). Cut them with scripts/demo/edit.sh"
else
	bash scripts/demo/edit.sh "$@"
fi
