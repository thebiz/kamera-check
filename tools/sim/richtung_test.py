import numpy as np, sys
src=open('proto.py').read()
pre=src.split("s=est_sigma(luma(blur))")[0]
pre=pre.replace("blur=np.stack([gaussian_filter(truth[...,c],SIG) for c in range(3)],-1)","blur=np.stack([gaussian_filter(truth[...,c],(SY,SX)) for c in range(3)],-1)")
pre=pre.replace("SIG=float(sys.argv[1]) if len(sys.argv)>1 else 4.0","SX=float(sys.argv[1]); SY=float(sys.argv[2]); SIG=max(SX,SY)")
pre=pre.replace("sess=ort.InferenceSession(sys.argv[2] if len(sys.argv)>2 else '/home/claude/kamera-check/app/src/main/assets/sr128.onnx')","sess=ort.InferenceSession('/home/claude/kamera-check/app/src/main/assets/sr128.onnx')")
pre=pre.replace("direct='strong' in (sys.argv[2] if len(sys.argv)>2 else '')","direct=False")
exec(pre)
def est_xy(y):
    sx=sy=2.0
    for _ in range(4):
        win=max(5,int(6*max(sx,sy))|1)
        gx=sobel(y,1)/8; gy=sobel(y,0)/8; g=np.hypot(gx,gy)
        r=maximum_filter(y,win)-minimum_filter(y,win)
        peak=(g==maximum_filter(g,3))&(r>0.15)&(g>0.01)
        sig=r/(np.maximum(g,1e-6)*np.sqrt(2*np.pi))
        vx=peak&(np.abs(gx)>2*np.abs(gy)); vy=peak&(np.abs(gy)>2*np.abs(gx))
        def lowmed(v):
            a=np.sort(sig[v]); return float(np.median(a[:max(20,len(a)//2)])) if len(a)>=20 else None
        ax=lowmed(vx); ay=lowmed(vy)
        if ax is None and ay is None: break
        sx=ax if ax is not None else ay; sy=ay if ay is not None else ax
    return sx,sy
def tgt(s): return min(1.8,max(1.0,0.9+0.12*s))
sx,sy=est_xy(luma(blur)); s=est_sigma(luma(blur))
print(f'echt σx={SX} σy={SY}  geschätzt σx={sx:.2f} σy={sy:.2f} iso={s:.2f}')
fi=min(1,tgt(s)/s); iso=resize(ai(resize(blur,int(W*fi),int(H*fi))),W,H)
fx=min(1,tgt(sx)/sx); fy=min(1,tgt(sy)/sy); an=resize(ai(resize(blur,int(W*fx),int(H*fy))),W,H)
e=lambda v: np.sqrt(np.mean((v-truth)**2))
print(f'gleichmäßig verkleinert {e(iso):.4f}   richtungsabhängig {e(an):.4f}')
from PIL import ImageDraw, ImageFont
fnt=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',22)
rows=[('Wahrheit',truth),('verwackelt (waagerecht)',blur),('gleichmäßig verkleinert + KI',iso),('richtungsabhängig + KI',an)]
c=Image.new('RGB',(W,(H//2+6)*len(rows)),'white')
for i,(n,a) in enumerate(rows):
  t=Image.fromarray((np.clip(a,0,1)*255).astype(np.uint8)).crop((0,40,W,40+H//2)); ImageDraw.Draw(t).text((8,H//2-30),n,font=fnt,fill=(255,0,0)); c.paste(t,(0,i*(H//2+6)))
c.save(f'aniso_{int(SX)}_{int(SY)}.jpg',quality=88)
