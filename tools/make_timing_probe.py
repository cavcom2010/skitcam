#!/usr/bin/env python3
"""Create NEW bounded timing probes, with aligned flash/chirp cues and delayed audio starts."""
import argparse
import json
import subprocess
import uuid
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('root',type=Path)
a=p.parse_args()
for variant,offsets in [('zero',[0.0,0.0]),('offset',[.15,.01])]:
    folder=a.root/variant
    folder.mkdir(parents=True,exist_ok=False)
    clips=[]
    for index,(color,tone,offset) in enumerate(zip(['red','blue'],[220,660],offsets),1):
        target=folder/f'segment_{index:03d}.mp4'
        flash="color=c=%s:s=1280x720:r=30:d=3,drawbox=c=white:t=fill:enable='between(t,1,1.04)'"%color
        chirp=f'if(between(t,{1-offset:.6f},{1.04-offset:.6f}),0.6*sin(2*PI*1800*t),0.02*sin(2*PI*{tone}*t))'
        subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i',flash,'-f','lavfi','-i',f"aevalsrc='{chirp}':s=48000:d={3-offset:.6f}",
                        '-af',f'asetpts=PTS+{offset}/TB','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-bf','0','-g','90',
                        '-c:a','aac','-b:a','128k','-ac','2',str(target)],check=True)
        duration=float(json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-of','json',str(target)]))['format']['duration'])
        clips.append({'id':str(uuid.uuid4()),'duration':round(duration*1000),'start':0,'end':round(duration*1000)})
    (folder/'export').mkdir()
    (folder/'export/job.json').write_text(json.dumps({'spec':clips}))
