#!/usr/bin/env bash
# Cuts the launch media from the last recording (build/demo/raw, see record.sh) into docs/images/launch:
# demo.mp4 (1080p60, Twitter/X), demo-readme.mp4 (720p30, under GitHub's 10 MB), demo.gif and demo-poster.png.
#
# The soundtrack is the game's own music, read from the Minecraft assets the dev client downloaded on this machine
# (it is Mojang's and is never copied into the repository; only the exported video carries it).
#   T3CRAFT_DEMO_MUSIC=sweden           another track from minecraft/sounds/music/game, or a path to any audio file
#   T3CRAFT_DEMO_MUSIC_START=12.5       seconds into the track to start from (default: 13.4 for wet_hands, else its first sound)
#   T3CRAFT_DEMO_ASSETS=<dir>           an assets directory (indexes/ and objects/) to look in first
#   scripts/demo/edit.sh --silent       export without a soundtrack (also T3CRAFT_DEMO_SILENT=1)
#   T3CRAFT_DEMO_DROP=talk              leave shots out of the cut (to shorten it)
#   T3CRAFT_DEMO_TRIM=dash:12:0         take frames off the head and tail of shots
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

# ---- The cut. Shots are whole takes of real play, in story order; marks.txt says where each beat fell. ----
# cut.py writes the ffmpeg filter graph: per shot, the frames to keep and the push-ins on small UI (the toast, the cards).
# A push-in holds on an exact crop scaled with nearest neighbour, so the game's pixel font stays crisp.
python3 - "$raw" "$tmp" <<'PY' > "$tmp/cut.txt"
import sys
raw, tmp = sys.argv[1], sys.argv[2]
marks = {}
for line in open(raw + '/marks.txt'):
    shot, label, frame = line.split()
    marks.setdefault(shot, {})[label] = int(frame)
def mark(shot, label):
    if label not in marks.get(shot, {}): sys.exit(f"The recording has no '{label}' mark in the {shot} shot ({raw}/marks.txt); re-record it")
    return marks[shot][label]
W, H = 1920, 1080
RAMP_IN, RAMP_OUT = 7, 9
ZOOM = 1.5
def window(shot):
    """The top of the 720-line window a card is shown in: just under its agent's name tag, and never above the card."""
    return max(250, min(mark(shot, 'tag') + 8, 330))
# A push-in is a list of steps on one shot: ('in', frame, x, y) ramps in to the 1.5x window whose corner is (x, y);
# ('to', frame, x, y) moves the window there over PAN frames (or at once with ('cut', ...), when the screen itself
# changes on that frame); ('out', frame) ramps back to the whole frame.
PAN = 22
laptop = mark('ship', 'laptop')
shots = [
    ('hook', 0, None, []), ('chop', 0, None, []), ('ping', 0, None, []),
    ('dash', 0, None, []), ('burst', 0, None, []), ('floor', 0, None, []), ('row', 0, None, []), ('arrive', 0, None, []),
    # The card: a window that starts just under the agent's name tag, so the tag is out of frame rather than cut by it.
    ('card', 0, None, [('in', mark('card', 'card') + 4, 600, window('card')), ('out', mark('card', 'closed'))]),
    # From where the agent's name tag has settled as it sits.
    ('desk', 14, None, []),
    # From the helper walking in, under the line about it, to its answer.
    ('talk', max(0, mark('talk', 'caption') - 2), mark('talk', 'second') + 74, []),
    # The card, then the laptop: first its list of every agent, then across to the thread and the reply box.
    ('ship', 0, None, [('in', mark('ship', 'card') + 4, 600, window('ship')), ('cut', laptop, 24, 96), ('to', laptop + 84, 640, 360), ('out', mark('ship', 'sent') + 42)]),
    ('cheer', 0, None, []), ('end', 0, None, []),
]
import os
skip = set(filter(None, os.environ.get('T3CRAFT_DEMO_DROP', '').split(',')))
# T3CRAFT_DEMO_TRIM=dash:12:0,burst:0:9 takes frames off the head and tail of shots.
trims = {name: (int(head), int(tail)) for name, head, tail in (item.split(':') for item in filter(None, os.environ.get('T3CRAFT_DEMO_TRIM', '').split(',')))}
graph, labels, total, cuts, inputs = [], [], 0, [], []
ease = lambda u: f'({u})*({u})*(3-2*({u}))'
cw, ch = round(W / ZOOM / 2) * 2, round(H / ZOOM / 2) * 2
for name, first, last, steps in shots:
    if name in skip: continue
    if not os.path.getsize(f'{raw}/seg-{name}.mp4'): sys.exit(f'Missing shot {raw}/seg-{name}.mp4; re-record')
    last = mark(name, 'end') if last is None else last
    first, last = first + trims.get(name, (0, 0))[0], last - trims.get(name, (0, 0))[1]
    i = len(inputs); inputs.append(f'{raw}/seg-{name}.mp4')
    pieces, at, where = [], first, None
    def piece(a, b, chain=''):
        if b > a: pieces.append(f'trim=start_frame={a}:end_frame={b},setpts=PTS-STARTPTS{chain}')
    def held(a, b):   # the exact crop, scaled with nearest neighbour: the pixel font stays crisp
        piece(a, b, f',crop={cw}:{ch}:{round(where[0])}:{round(where[1])},scale={W}:{H}:flags=neighbor' if where else '')
    def moving(a, b, z0, z1, p0, p1):   # zoompan counts its frames from 1
        k = ease(f'on/{b - a + 1}')
        piece(a, b, f",zoompan=z='{z0:.4f}+{z1 - z0:.4f}*({k})':x='{p0[0]:.1f}+{p1[0] - p0[0]:.1f}*({k})':y='{p0[1]:.1f}+{p1[1] - p0[1]:.1f}*({k})':d=1:s={W}x{H}:fps=60")
    for step in steps:
        kind, frame = step[0], max(first + 1, min(step[1], last - 1))
        held(at, frame)
        if kind == 'in': where = (step[2], step[3]); moving(frame, frame + RAMP_IN, 1, ZOOM, (0, 0), where); at = frame + RAMP_IN
        elif kind == 'cut': where = (step[2], step[3]); at = frame
        elif kind == 'to': moving(frame, frame + PAN, ZOOM, ZOOM, where, (step[2], step[3])); where = (step[2], step[3]); at = frame + PAN
        elif kind == 'out': moving(frame, min(frame + RAMP_OUT, last), ZOOM, 1, where, (0, 0)); where = None; at = min(frame + RAMP_OUT, last)
    held(at, last)
    if len(pieces) == 1: graph.append(f'[{i}:v]{pieces[0]},setsar=1[v{i}]')
    else:
        graph.append(f'[{i}:v]split={len(pieces)}' + ''.join(f'[s{i}_{n}]' for n in range(len(pieces))))
        for n, chain in enumerate(pieces): graph.append(f'[s{i}_{n}]{chain},setsar=1,format=yuv420p[p{i}_{n}]')
        graph.append(''.join(f'[p{i}_{n}]' for n in range(len(pieces))) + f'concat=n={len(pieces)}:v=1:a=0[v{i}]')
    labels.append(f'[v{i}]'); total += last - first; cuts.append(f'{name}@{total / 60:.2f}')
graph.append(''.join(labels) + f'concat=n={len(labels)}:v=1:a=0[v]')
open(tmp + '/graph.txt', 'w').write(';\n'.join(graph))
print(total); print(' '.join(cuts))
for path in inputs: print(path)
PY
frames=$(sed -n 1p "$tmp/cut.txt"); cuts=$(sed -n 2p "$tmp/cut.txt")
inputs=(); while IFS= read -r path; do inputs+=(-i "$path"); done < <(sed -n '3,$p' "$tmp/cut.txt")
length=$(awk -v f="$frames" 'BEGIN { printf "%.3f", f / 60 }')
echo "Cut: $length s; shots end at $cuts"
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
	# Wet Hands opens with ten seconds of near silence, so the default starts 13.4 s in, on the phrase where it picks up.
	start=${T3CRAFT_DEMO_MUSIC_START:-}; [ -z "$start" ] && [ "$track" = wet_hands ] && start=13.4
	if [ -n "$start" ]; then lead="atrim=start=$start,asetpts=PTS-STARTPTS"; else lead="silenceremove=start_periods=1:start_threshold=-48dB"; fi
	fade_out=$(awk -v n="$length" 'BEGIN { printf "%.3f", n - 2.6 }')
	shape="aformat=sample_rates=48000:channel_layouts=stereo,$lead,atrim=duration=$length,asetpts=PTS-STARTPTS,afade=t=in:d=0.2,afade=t=out:st=$fade_out:d=2.6"
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
	"$ffmpeg" -y -loglevel error "${inputs[@]}" -filter_complex "$(cat "$tmp/graph.txt")" -map "[v]" -r 60 -c:v libx264 -preset slow -crf $crf -profile:v high -level 4.2 "${video[@]}" -an "$tmp/demo-video.mp4"
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
gif_a_from=$(least $(( $(mark ping ping) - 20 )) 0); gif_a_to=$(mark ping end)
gif_b_from=$(least $(( $(mark card click) - 20 )) 0); gif_b_to=$(( $(mark card closed) + 30 ))
for try in "800 12 128" "800 12 80" "720 12 64" "640 12 64" "640 10 48" "560 10 48" "480 10 32"; do
	set -- $try
	"$ffmpeg" -y -loglevel error -i "$raw/seg-ping.mp4" -i "$raw/seg-card.mp4" -filter_complex "\
[0:v]trim=start_frame=$gif_a_from:end_frame=$gif_a_to,setpts=PTS-STARTPTS[a];[1:v]trim=start_frame=$gif_b_from:end_frame=$gif_b_to,setpts=PTS-STARTPTS[b];\
[a][b]concat=n=2:v=1:a=0,fps=$2,scale=$1:-1:flags=lanczos,split[x][y];[x]palettegen=max_colors=$3:stats_mode=diff[p];[y][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
		-loop 0 "$dest/demo.gif"
	[ "$(size "$dest/demo.gif")" -le 7900000 ] && break
done

# Poster: the lit office at dusk under the title, from the last frames of the video.
poster=$(( $(mark end end) - 6 ))
"$ffmpeg" -y -loglevel error -i "$raw/seg-end.mp4" -vf "select='eq(n\,$poster)'" -frames:v 1 "$dest/demo-poster.png"

echo "Wrote:"
for f in demo.mp4 demo-readme.mp4 demo.gif demo-poster.png; do
	printf '  %s  %s bytes' "$PWD/$dest/$f" "$(size "$dest/$f")"
	case $f in *.mp4) printf '  %s s  audio: %s' "$("$ffprobe" -v error -show_entries format=duration -of csv=p=0 "$dest/$f")" "$("$ffprobe" -v error -select_streams a -show_entries stream=codec_name -of csv=p=0 "$dest/$f" | tr '\n' ' ')";; esac
	echo
done
