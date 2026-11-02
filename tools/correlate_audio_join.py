#!/usr/bin/env python3
"""Compare known source PCM windows to a COPY of an export. Not a lip-sync assessment.
Usage: script OUTPUT SOURCE1 SOURCE2 --join-seconds FIRST_CLIP_PRESENTATION_DURATION
Requires numpy and ffmpeg. Reports confidence; weak peaks must not establish alignment.
"""
import argparse
import json
import subprocess
import numpy as np

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('output');p.add_argument('sources',nargs=2);p.add_argument('--join-seconds',type=float,required=True)
a=p.parse_args()
def pcm(path):
    raw=subprocess.check_output(['ffmpeg','-v','error','-copyts','-i',path,'-vn','-af','aresample=48000:async=0:first_pts=0','-ac','1','-f','f32le','-'])
    return np.frombuffer(raw,dtype='<f4').astype(float)
out=pcm(a.output)
rows=[]
for index,source in enumerate(a.sources):
    src=pcm(source);offset=0 if index==0 else a.join_seconds
    for start in [.3,.9]:
        template=src[round(start*48000):round((start+.25)*48000)].copy();template-=template.mean()
        expected=start+offset;lo=max(0,round((expected-.2)*48000));hi=min(len(out),round((expected+.45)*48000))
        x=out[lo:hi].copy();x-=x.mean()
        if len(x)<len(template) or len(template)==0: continue
        length=1<<(len(x)+len(template)-1).bit_length()
        corr=np.fft.irfft(np.fft.rfft(x,length)*np.conj(np.fft.rfft(template,length)),length)[:len(x)-len(template)+1]
        cs=np.r_[0,np.cumsum(x*x)];energy=cs[len(template):]-cs[:-len(template)]
        denominator=np.sqrt(np.maximum(0,energy)*np.sum(template*template))
        normal=np.divide(corr,denominator,out=np.zeros_like(corr),where=denominator>0)
        best=int(normal.argmax());actual=(lo+best)/48000
        rows.append({'clip':index+1,'source_window_s':start,'correlation':float(normal[best]),'displacement_ms':(actual-expected)*1000})
print(json.dumps(rows,indent=2))
