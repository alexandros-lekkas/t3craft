#!/usr/bin/env bash
# Cuts the launch media from the last recording (build/demo/raw, see record.sh) into docs/images/launch:
# demo.mp4 (1080p60, Twitter/X), demo-readme.mp4 (720p30, under GitHub's 10 MB), demo.gif and demo-poster.png.
#
# The soundtrack is the game's own music, read from the Minecraft assets the dev client downloaded on this machine
# (it is Mojang's and is never copied into the repository; only the exported video carries it).
#   T3CRAFT_DEMO_MUSIC=sweden           another track from minecraft/sounds/music/game, or a path to any audio file
#   T3CRAFT_DEMO_MUSIC_START=12.5       seconds into the track to start from (default: its first sound)
#   T3CRAFT_DEMO_ASSETS=<dir>           an assets directory (indexes/ and objects/) to look in first
#   scripts/demo/edit.sh --silent       export without a soundtrack (also T3CRAFT_DEMO_SILENT=1)
set -euo pipefail
cd "$(dirname "$0")/../.."
raw=build/demo/raw
tmp=build/demo/tmp
dest=docs/images/launch
ffmpeg=${FFMPEG:-$(command -v ffmpeg)}
ffprobe=${FFPROBE:-$(dirname "$ffmpeg")/ffprobe}
silent=${T3CRAFT_DEMO_SILENT:-}
for arg in "$@"; do [ "$arg" = --silent ] && silent=1; done
mkdir -p "$dest" "$tmp"
[ -s "$raw/marks.txt" ] || { echo "No recording in $raw; run scripts/demo/record.sh first" >&2; exit 1; }
mark() {
	local frame
	frame=$(awk -v s="$1" -v l="$2" '$1==s && $2==l {print $3}' "$raw/marks.txt" | tail -1)
	[ -n "$frame" ] || { echo "The recording has no '$2' mark in the $1 shot ($raw/marks.txt); re-record it" >&2; exit 1; }
	echo "$frame"
}
size() { stat -f %z "$1" 2>/dev/null || stat -c %s "$1"; }
least() { [ "$1" -gt "$2" ] && echo "$1" || echo "$2"; }

# ---- The cut. Every shot is in motion from its first frame to its last, so the cuts land on a moving camera. ----
# name:first frame:last frame (blank = whole shot)
follow_from=$(least $(( $(mark follow seated) - 84 )) 0); follow_to=$(( $(mark follow seated) + 72 ))
talk_from=$(least $(( $(mark talk second) - 132 )) $(least $(( $(mark talk first) - 12 )) 0)); talk_to=$(( $(mark talk second) + 72 ))
shots=("hook::" "survival::" "arc::" "card::" "follow:$follow_from:$follow_to" "talk:$talk_from:$talk_to" "type::" "dusk::" "end::")
inputs=(); graph=""; labels=""; n=0; frames=0; cuts=""
for shot in "${shots[@]}"; do
	IFS=: read -r name from to <<< "$shot"
	[ -s "$raw/seg-$name.mp4" ] || { echo "Missing shot $raw/seg-$name.mp4; re-record" >&2; exit 1; }
	inputs+=(-i "$raw/seg-$name.mp4")
	if [ -n "$from" ]; then
		graph+="[$n:v]trim=start_frame=$from:end_frame=$to,setpts=PTS-STARTPTS[v$n];"; frames=$(( frames + to - from ))
	else
		graph+="[$n:v]setpts=PTS-STARTPTS[v$n];"; frames=$(( frames + $(mark "$name" end) ))
	fi
	cuts+=" $name@$(awk -v f="$frames" 'BEGIN { printf "%.2f", f / 60 }')"
	labels+="[v$n]"; n=$(( n + 1 ))
done
graph+="${labels}concat=n=$n:v=1:a=0[v]"
length=$(awk -v f="$frames" 'BEGIN { printf "%.3f", f / 60 }')
echo "Cut: $length s; shots end at$cuts"
video=(-pix_fmt yuv420p -colorspace bt709 -color_primaries bt709 -color_trc bt709 -movflags +faststart)

# ---- The soundtrack: the game's own music from the local Minecraft assets, levelled to -16 LUFS. ----
audio=()
if [ -z "$silent" ]; then
	track=${T3CRAFT_DEMO_MUSIC:-wet_hands}
	if [ -f "$track" ]; then music=$track; else
		key="minecraft/sounds/music/game/$track.ogg"
		music=$(python3 - "$key" "${T3CRAFT_DEMO_ASSETS:-}" "$HOME/.gradle/caches/fabric-loom/assets" "$PWD/.gradle/loom-cache/assets" "$PWD/run/assets" <<'PY'
import glob, json, os, sys
key = sys.argv[1]
for root in [r for r in sys.argv[2:] if r]:
    for index in sorted(glob.glob(os.path.join(root, 'indexes', '*.json')), reverse=True):
        try: entry = json.load(open(index)).get('objects', {}).get(key)
        except (OSError, ValueError): continue
        if not entry: continue
        path = os.path.join(root, 'objects', entry['hash'][:2], entry['hash'])
        if os.path.isfile(path):
            print(path); sys.exit(0)
PY
) || true
		if [ -z "$music" ]; then
			echo "No soundtrack: looked for $key through the asset indexes (indexes/*.json) under" >&2
			echo "  ${T3CRAFT_DEMO_ASSETS:+$T3CRAFT_DEMO_ASSETS, }$HOME/.gradle/caches/fabric-loom/assets, $PWD/.gradle/loom-cache/assets and $PWD/run/assets" >&2
			echo "Run the dev client once (./gradlew runClient) so the game's assets are downloaded, point T3CRAFT_DEMO_ASSETS at an" >&2
			echo "assets directory, set T3CRAFT_DEMO_MUSIC to an audio file, or pass --silent to export without music." >&2
			exit 1
		fi
	fi
	# Start on the track's first sound (or where asked), fade in briefly, fade out under the end card.
	if [ -n "${T3CRAFT_DEMO_MUSIC_START:-}" ]; then lead="atrim=start=$T3CRAFT_DEMO_MUSIC_START,asetpts=PTS-STARTPTS"; else lead="silenceremove=start_periods=1:start_threshold=-48dB"; fi
	fade_out=$(awk -v n="$length" 'BEGIN { printf "%.3f", n - 2.6 }')
	shape="aformat=sample_rates=48000:channel_layouts=stereo,$lead,atrim=duration=$length,asetpts=PTS-STARTPTS,afade=t=in:d=0.12,afade=t=out:st=$fade_out:d=2.6"
	level=$("$ffmpeg" -hide_banner -nostats -i "$music" -af "$shape,ebur128=framelog=quiet" -f null - 2>&1 | awk '/I:/ { v = $2 } END { print v }')
	gain=$(awk -v i="$level" 'BEGIN { printf "%.2f", -16 - i }')
	"$ffmpeg" -y -loglevel error -i "$music" -af "$shape,volume=${gain}dB,alimiter=limit=0.84:level=false" -c:a pcm_s16le "$tmp/music.wav"
	echo "Soundtrack: $track ($music), $level LUFS raised by $gain dB"
	audio=(-i "$tmp/music.wav")
fi
mux() { # video in, out, audio bitrate
	if [ ${#audio[@]} -gt 0 ]; then "$ffmpeg" -y -loglevel error -i "$1" "${audio[@]}" -map 0:v -map 1:a -c:v copy -c:a aac -b:a "$3" -shortest -movflags +faststart "$2"
	else "$ffmpeg" -y -loglevel error -i "$1" -c copy -an -movflags +faststart "$2"; fi
}

# Twitter/X: 1080p60 H.264 under 25 MB. The quality steps down only if a take comes out too big.
for crf in 20 22 24 26 28; do
	"$ffmpeg" -y -loglevel error "${inputs[@]}" -filter_complex "$graph" -map "[v]" -r 60 -c:v libx264 -preset slow -crf $crf -profile:v high -level 4.2 "${video[@]}" -an "$tmp/demo-video.mp4"
	mux "$tmp/demo-video.mp4" "$dest/demo.mp4" 192k
	[ "$(size "$dest/demo.mp4")" -le 24000000 ] && break
done
# GitHub README upload: 720p30 under 10 MB.
for crf in 24 26 28 30 32; do
	"$ffmpeg" -y -loglevel error -i "$tmp/demo-video.mp4" -vf "fps=30,scale=1280:720:flags=lanczos" -c:v libx264 -preset slow -crf $crf -profile:v high "${video[@]}" -an "$tmp/readme-video.mp4"
	mux "$tmp/readme-video.mp4" "$dest/demo-readme.mp4" 128k
	[ "$(size "$dest/demo-readme.mp4")" -le 9500000 ] && break
done

# GIF (silent): the ping arriving while the player chops a tree, then the approval on the agent's card.
gif_a_from=$(least $(( $(mark survival ping) - 30 )) 0); gif_a_to=$(mark survival end)
gif_b_from=0; gif_b_to=$(mark card end)
for try in "800 12 128" "800 12 80" "720 12 64" "640 12 64" "640 10 48" "560 10 48" "480 10 32"; do
	set -- $try
	"$ffmpeg" -y -loglevel error -i "$raw/seg-survival.mp4" -i "$raw/seg-card.mp4" -filter_complex "\
[0:v]trim=start_frame=$gif_a_from:end_frame=$gif_a_to,setpts=PTS-STARTPTS[a];[1:v]trim=start_frame=$gif_b_from:end_frame=$gif_b_to,setpts=PTS-STARTPTS[b];\
[a][b]concat=n=2:v=1:a=0,fps=$2,scale=$1:-1:flags=lanczos,split[x][y];[x]palettegen=max_colors=$3:stats_mode=diff[p];[y][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
		-loop 0 "$dest/demo.gif"
	[ "$(size "$dest/demo.gif")" -le 7900000 ] && break
done

# Poster: the lit office at dusk under the end card.
poster=$(( $(mark end end) - 6 ))
"$ffmpeg" -y -loglevel error -i "$raw/seg-end.mp4" -vf "select='eq(n\,$poster)'" -frames:v 1 "$dest/demo-poster.png"

echo "Wrote:"
for f in demo.mp4 demo-readme.mp4 demo.gif demo-poster.png; do
	printf '  %s  %s bytes' "$PWD/$dest/$f" "$(size "$dest/$f")"
	case $f in *.mp4) printf '  %s s  audio: %s' "$("$ffprobe" -v error -show_entries format=duration -of csv=p=0 "$dest/$f")" "$("$ffprobe" -v error -select_streams a -show_entries stream=codec_name -of csv=p=0 "$dest/$f" | tr '\n' ' ')";; esac
	echo
done
