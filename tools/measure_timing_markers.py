#!/usr/bin/env python3
"""Read-only cue measurement for make_timing_probe.py fixtures. Requires numpy/ffmpeg."""
import json
import subprocess
import sys
import numpy as np

for path in sys.argv[1:]:
    frames=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v','-show_frames','-show_entries','frame=pts_time','-of','json',path]))['frames']
    rgb=subprocess.check_output(['ffmpeg','-v','error','-i',path,'-vf','scale=1:1','-pix_fmt','rgb24','-fps_mode','passthrough','-f','rawvideo','-'])
    pixels=np.frombuffer(rgb,dtype=np.uint8).reshape(-1,3)
    assert len(frames)==len(pixels)
    white=np.min(pixels,axis=1)>180
    flashes=[float(frames[i]['pts_time']) for i in range(len(white)) if white[i] and (i==0 or not white[i-1])]
    # Preserve source timeline, filling only analysis-output leading timestamps to map byte positions.
    raw=subprocess.check_output(['ffmpeg','-v','error','-copyts','-i',path,'-vn','-af','aresample=48000:async=0:first_pts=0','-ac','1','-f','f32le','-'])
    audio=np.frombuffer(raw,dtype='<f4')
    peaks=np.array([np.max(np.abs(audio[i:i+240])) for i in range(0,len(audio),240)])
    loud=peaks>.2
    chirps=[i*.005 for i in range(len(loud)) if loud[i] and (i==0 or not loud[i-1])]
    print(json.dumps({'path':path,'flash_s':flashes,'chirp_window_s':chirps,
                      'chirp_minus_flash_ms':[(a-v)*1000 for a,v in zip(chirps,flashes)]}))
