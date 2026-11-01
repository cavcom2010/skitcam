#!/usr/bin/env python3
"""Independently inspect the dedicated M6 yellow/red/blue trimmed fixture.

Requires host ffmpeg/ffprobe. Takes only a COPY of the output as its argument;
never changes that file. Expected source flash=1.1s, chirp=1.08s, trims=[.35,1.75).
"""
import json
import struct
import subprocess
import sys

path = sys.argv[1]
def run(*args):
    return subprocess.check_output(args)

probe = json.loads(run('ffprobe', '-v', 'error', '-show_streams', '-of', 'json', path))
tracks = {t['codec_type']: t for t in probe['streams']}
assert tracks['video']['codec_name'] == 'h264'
assert tracks['audio']['codec_name'] == 'aac'
assert int(tracks['audio']['sample_rate']) == 48000
assert int(tracks['audio']['channels']) == 2
for track in tracks.values():
    assert abs(float(track['duration']) - 4.2) <= .1, track
assert abs(float(tracks['audio']['duration']) - float(tracks['video']['duration'])) <= .1
frames = json.loads(run('ffprobe', '-v', 'error', '-select_streams', 'v', '-show_frames',
                       '-show_entries', 'frame=pts_time', '-of', 'json', path))['frames']
rgb = run('ffmpeg', '-v', 'error', '-i', path, '-vf', 'scale=1:1', '-pix_fmt', 'rgb24',
          '-fps_mode', 'passthrough', '-f', 'rawvideo', '-')
assert len(rgb) == len(frames) * 3
changes = []
for i, frame in enumerate(frames):
    r, g, b = rgb[3*i:3*i+3]
    color = ('white' if min(r,g,b)>180 else 'yellow' if r>100 and g>100
             else 'red' if r>100 else 'blue' if b>100 else 'unexpected')
    if not changes or color != changes[-1][1]:
        changes.append((float(frame['pts_time']), color))
assert [c for _,c in changes] == ['yellow','white','yellow','red','white','red','blue','white','blue'], changes
flashes = [t for t,c in changes if c=='white']
for actual, expected in zip(flashes, [.75,2.15,3.55]):
    assert abs(actual-expected) <= .08, (actual,expected)
raw = run('ffmpeg', '-v', 'error', '-i', path, '-vn', '-ac', '1', '-ar', '48000', '-f', 'f32le', '-')
audio = struct.unpack('<%sf' % (len(raw)//4), raw)
bursts = []
previous = False
for i in range(0,len(audio)-480,480):
    loud = max(abs(v) for v in audio[i:i+480]) > .2
    if loud and not previous:
        bursts.append(i/48000)
    previous = loud
assert len(bursts)==3, bursts
for flash,chirp in zip(flashes,bursts):
    assert abs(flash-chirp)<=.08, (flash,chirp)
print(json.dumps({'tracks': {k: {f:v.get(f) for f in ['codec_name','duration','width','height','sample_rate','channels']} for k,v in tracks.items()},
                  'scene_changes':changes,'flash_times':flashes,'chirp_times':bursts},indent=2))
