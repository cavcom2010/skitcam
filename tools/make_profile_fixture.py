#!/usr/bin/env python3
"""Generate NEW synthetic 1080p/60 export fixtures; never modify existing media.

Requires ffmpeg/libx264. This is a pipeline fixture, not camera capability evidence.
The first three clips are reordered yellow/red/blue and trimmed [.35, 1.75).
The fourth green clip is discarded. check_export_fixture.py validates the output.
Upload the generated files tree only into a dedicated validation application.
"""
from pathlib import Path
import json,uuid,subprocess,hashlib,argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('root',type=Path)
parser.add_argument('--package',required=True)
args=parser.parse_args()
assert args.package.endswith(('.m9validation','.m10validation')), "Use the dedicated validation app"
root=args.root
root.mkdir(parents=True,exist_ok=False)
sid=str(uuid.uuid4());folder=root/'files'/'sessions'/sid;folder.mkdir(parents=True)
clips=[]
for i,color in enumerate(['red','blue','yellow','green'],1):
 p=folder/f'segment_{i:03}.mp4'
 video=f"color=c={color}:s=1920x1080:r=60:d=2,drawbox=c=white:t=fill:enable='between(t,1.1,1.14)'"
 audio="aevalsrc='if(between(t,1.08,1.12),0.6*sin(2*PI*1800*t),0.02*sin(2*PI*220*t))':s=48000:d=2"
 subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i',video,'-f','lavfi','-i',audio,'-c:v','libx264','-threads','1','-preset','ultrafast','-pix_fmt','yuv420p','-bf','0','-g','120','-c:a','aac','-b:a','128k','-ac','2',str(p)],check=True)
 clips.append(dict(order=i,id=str(uuid.uuid4()),path=f'/data/user/0/{args.package}/files/sessions/{sid}/{p.name}',durationMs=2000,orientationDegrees=270,videoRotationDegrees=0,kept=i!=4,trim=dict(sourceDurationMs=2000,trimStartMs=350,trimEndMs=1750)))
s=dict(version=6,lens='Rear',id=sid,createdAtMs=1,orientationDegrees=270,state='Completed',error=None,pending=None,segments=clips,playbackOrder=[clips[i]['id'] for i in [2,0,1]],captureProfile=dict(version=1,cameraId='0',settings=dict(quality='FHD',fps=60,stabilization=False,whiteBalance='AUTO'),width=1920,height=1080,legacy=False))
(folder/'session.json').write_text(json.dumps(s,indent=2))
(root/'session-id.txt').write_text(sid)
(root/'sources.sha256').write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in folder.glob('*.mp4'))+'\n')
print(sid)
