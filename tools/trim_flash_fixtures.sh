#!/usr/bin/env bash
# Generate disposable inputs for TrimPreviewFlashDeviceTest. Pass an output directory.
set -euo pipefail
out=${1:?Usage: bash tools/trim_flash_fixtures.sh OUTPUT_DIRECTORY}
mkdir -p "$out"
ffmpeg -nostdin -hide_banner -loglevel error -n \
    -f lavfi -i 'color=red:size=320x240:rate=25:duration=4' \
    -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=4' \
    -vf "drawbox=c=lime:t=fill:enable='gte(t,1)',drawbox=c=blue:t=fill:enable='gte(t,2)',drawbox=c=yellow:t=fill:enable='gte(t,3)'" \
    -c:v libx264 -pix_fmt yuv420p -g 25 -bf 0 -c:a aac "$out/first.mp4"
ffmpeg -nostdin -hide_banner -loglevel error -n \
    -f lavfi -i 'color=magenta:size=320x240:rate=25:duration=4' \
    -f lavfi -i 'sine=frequency=880:sample_rate=48000:duration=4' \
    -vf "drawbox=c=cyan:t=fill:enable='gte(t,2)'" \
    -c:v libx264 -pix_fmt yuv420p -g 25 -bf 0 -c:a aac "$out/second.mp4"
