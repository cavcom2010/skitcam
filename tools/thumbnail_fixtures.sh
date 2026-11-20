#!/usr/bin/env bash
# Generate the disposable input for ThumbnailReadabilityDeviceTest.
set -euo pipefail
out=${1:?Usage: bash tools/thumbnail_fixtures.sh OUTPUT_DIRECTORY}
mkdir -p "$out"
# Camera-like take: 2 s keyframe interval and a dark opening. Sampling at 500 ms
# with OPTION_CLOSEST_SYNC resolves to the t=0 keyframe, which is why this
# fixture reproduces the black Review thumbnail.
ffmpeg -nostdin -hide_banner -loglevel error -n \
    -f lavfi -i 'color=black:size=320x240:rate=25:duration=0.8' \
    -f lavfi -i 'color=blue:size=320x240:rate=25:duration=2.4' \
    -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=3.2' \
    -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -map 2:a \
    -c:v libx264 -pix_fmt yuv420p -g 50 -keyint_min 50 -sc_threshold 0 -c:a aac \
    "$out/darkstart.mp4"
