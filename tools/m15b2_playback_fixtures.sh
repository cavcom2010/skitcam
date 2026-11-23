#!/usr/bin/env bash
# Generate disposable, visually identifiable Play Skit and Review inputs.
set -euo pipefail
out=${1:?Usage: bash tools/m15b2_playback_fixtures.sh OUTPUT_DIRECTORY}
mkdir -p "$out"

make_clip() {
    local name=$1 first=$2 next=$3 duration=$4 frequency=$5
    ffmpeg -nostdin -hide_banner -loglevel error -n \
        -f lavfi -i "color=$first:size=720x1280:rate=25:duration=$duration" \
        -f lavfi -i "sine=frequency=$frequency:sample_rate=48000:duration=$duration" \
        -vf "drawbox=c=$next:t=fill:enable='gte(t,1)'" \
        -c:v libx264 -pix_fmt yuv420p -g 25 -bf 0 -c:a aac "$out/$name.mp4"
    ffmpeg -nostdin -hide_banner -loglevel error -xerror -i "$out/$name.mp4" \
        -map 0:v -map 0:a -f null -
}

make_clip first red lime 3.6 440
make_clip second blue yellow 4.4 660
make_clip third magenta cyan 2.8 880
