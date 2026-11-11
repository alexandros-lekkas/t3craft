#!/usr/bin/env bash
# Cuts the launch media from the last recording (build/demo/raw, see record.sh) into docs/images/launch:
# demo.mp4 (1080p60, Twitter/X), demo-readme.mp4 (720p30, under GitHub's 10 MB), demo.gif and demo-poster.png.
set -euo pipefail
cd "$(dirname "$0")/../.."
raw=build/demo/raw
dest=docs/images/launch
ffmpeg=${FFMPEG:-$(command -v ffmpeg)}
mkdir -p "$dest" build/demo/tmp
mark() { awk -v s="$1" -v l="$2" '$1==s && $2==l {print $3}' "$raw/marks.txt"; }
size() { stat -f %z "$1" 2>/dev/null || stat -c %s "$1"; }

# The talk shot starts when the helper walks in; keep the end of the walk, the whole first line and most of the reply.
talk_from=$(( $(mark talk first) - 40 )); [ "$talk_from" -lt 0 ] && talk_from=0
talk_to=$(( $(mark talk second) + 110 ))
last=$(( $(mark exterior end) + $(mark desks end) + $(mark needs end) + talk_to - talk_from + $(mark laptop end) + $(mark night end) ))
fade_out=$(awk -v n="$last" 'BEGIN { printf "%.3f", n / 60 - 0.6 }')
graph="[0:v]setpts=PTS-STARTPTS[a];[1:v]setpts=PTS-STARTPTS[b];[2:v]setpts=PTS-STARTPTS[c];\
[3:v]trim=start_frame=$talk_from:end_frame=$talk_to,setpts=PTS-STARTPTS[d];[4:v]setpts=PTS-STARTPTS[e];[5:v]setpts=PTS-STARTPTS[f];\
[a][b][c][d][e][f]concat=n=6:v=1:a=0,fade=t=in:st=0:d=0.4,fade=t=out:st=$fade_out:d=0.6"
inputs=(-i "$raw/seg-exterior.mp4" -i "$raw/seg-desks.mp4" -i "$raw/seg-needs.mp4" -i "$raw/seg-talk.mp4" -i "$raw/seg-laptop.mp4" -i "$raw/seg-night.mp4")
tags=(-pix_fmt yuv420p -colorspace bt709 -color_primaries bt709 -color_trc bt709 -movflags +faststart -an)

# Twitter/X: 1080p60 H.264 under 25 MB. The quality steps down only if a take comes out too big.
for crf in 20 22 24 26 28; do
	"$ffmpeg" -y -loglevel error "${inputs[@]}" -filter_complex "$graph[v]" -map "[v]" -r 60 -c:v libx264 -preset slow -crf $crf -profile:v high -level 4.2 "${tags[@]}" "$dest/demo.mp4"
	[ "$(size "$dest/demo.mp4")" -le 24000000 ] && break
done
# GitHub README upload: 720p30 under 10 MB.
for crf in 24 26 28 30 32; do
	"$ffmpeg" -y -loglevel error -i "$dest/demo.mp4" -vf "fps=30,scale=1280:720:flags=lanczos" -c:v libx264 -preset slow -crf $crf -profile:v high "${tags[@]}" "$dest/demo-readme.mp4"
	[ "$(size "$dest/demo-readme.mp4")" -le 9500000 ] && break
done

# GIF: the approval on the agent card, then the hand-off talk. Both are still-camera shots, which keeps it small.
gif_a_from=$(( $(mark needs firstperson) - 24 )); gif_a_to=$(( $(mark needs closed) + 40 ))
gif_b_from=$(( $(mark talk first) - 20 )); gif_b_to=$(( $(mark talk second) + 70 ))
for try in "960 12 160" "960 12 96" "960 12 64" "880 12 64" "800 12 64" "720 12 48"; do
	set -- $try
	"$ffmpeg" -y -loglevel error -i "$raw/seg-needs.mp4" -i "$raw/seg-talk.mp4" -filter_complex "\
[0:v]trim=start_frame=$gif_a_from:end_frame=$gif_a_to,setpts=PTS-STARTPTS[a];[1:v]trim=start_frame=$gif_b_from:end_frame=$gif_b_to,setpts=PTS-STARTPTS[b];\
[a][b]concat=n=2:v=1:a=0,fps=$2,scale=$1:-1:flags=lanczos,split[x][y];[x]palettegen=max_colors=$3:stats_mode=diff[p];[y][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
		-loop 0 "$dest/demo.gif"
	[ "$(size "$dest/demo.gif")" -le 8000000 ] && break
done

# Poster: the lit office at dusk with the title on it.
poster=$(( $(mark night caption) + 200 ))
"$ffmpeg" -y -loglevel error -i "$raw/seg-night.mp4" -vf "select='eq(n\,$poster)'" -frames:v 1 "$dest/demo-poster.png"
ls -l "$dest"/demo*
