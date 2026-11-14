import numpy as np, onnxruntime as ort, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy.ndimage import gaussian_filter, sobel, maximum_filter, minimum_filter
rng=np.random.default_rng(3)
# Szene: dunkle Schrift auf hellem Karton (wie DREAME), Arbeitsauflösung = Mehrbild-Raster
W,H=640,300
img=Image.new('RGB',(W,H),(196,176,150)); d=ImageDraw.Draw(img)
f=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',150)
d.text((30,60),'DREAME',font=f,fill=(70,62,50))
truth=np.asarray(img).astype(np.float32)/255
SIG=float(sys.argv[1]) if len(sys.argv)>1 else 4.0
blur=np.stack([gaussian_filter(truth[...,c],SIG) for c in range(3)],-1)
blur=np.clip(blur+rng.normal(0,0.008,blur.shape),0,1)
Image.fromarray((blur*255).astype(np.uint8)).save('blur.png')

def est_sigma(y):
    # Unschärfe aus Kanten: Sprung der Höhe A, Gauß σ → max. Gradient A/(σ√2π)
    s=2.0
    for _ in range(4):
        win=max(5,int(6*s)|1)
        gx=sobel(y,1)/8; gy=sobel(y,0)/8; g=np.hypot(gx,gy)
        rng_=maximum_filter(y,win)-minimum_filter(y,win)
        peak=(g==maximum_filter(g,3))&(rng_>0.15)&(g>0.01)
        if peak.sum()<20: break
        sig=rng_[peak]/(g[peak]*np.sqrt(2*np.pi))
        s=float(np.median(np.sort(sig)[:max(20,len(sig)//2)]))  # untere Hälfte: echte Sprünge
    return s

def rl(y,s,it=12):
    est=y.copy()
    for _ in range(it):
        c=gaussian_filter(est,s); ratio=y/np.maximum(c,1e-3)
        est=est*gaussian_filter(ratio,s)
        est=np.clip(est,0,1.2)
    return est

sess=ort.InferenceSession(sys.argv[2] if len(sys.argv)>2 else '/home/claude/kamera-check/app/src/main/assets/sr128.onnx')
direct='strong' in (sys.argv[2] if len(sys.argv)>2 else '')
T=128;OV=8;ST=T-2*OV
def ai(img):
  H,W,_=img.shape; out=np.zeros((H*4,W*4,3),np.float32)
  st=lambda n:[0] if n<=T else list(range(0,n,ST))
  for ty in st(H):
    for tx in st(W):
      y0=0 if H<=T else min(max(ty-OV,0),H-T); x0=0 if W<=T else min(max(tx-OV,0),W-T)
      yy=np.minimum(np.arange(T)+y0,H-1); xx=np.minimum(np.arange(T)+x0,W-1)
      tile=img[yy][:,xx]
      o=sess.run(None,{'input':tile.transpose(2,0,1)[None].astype(np.float32)})[0][0]
      if direct: sr=o.transpose(1,2,0)
      else: sr=o.reshape(3,4,4,T,T).transpose(3,1,4,2,0).reshape(T*4,T*4,3)+np.repeat(np.repeat(tile,4,0),4,1)
      vy0,vy1=(0,H) if H<=T else (ty,min(ty+ST,H)); vx0,vx1=(0,W) if W<=T else (tx,min(tx+ST,W))
      out[vy0*4:vy1*4,vx0*4:vx1*4]=sr[(vy0-y0)*4:(vy1-y0)*4,(vx0-x0)*4:(vx1-x0)*4]
  return np.clip(out,0,1)
def resize(a,w,h): return np.asarray(Image.fromarray((np.clip(a,0,1)*255).astype(np.uint8)).resize((w,h),Image.LANCZOS)).astype(np.float32)/255
def luma(a): return 0.299*a[...,0]+0.587*a[...,1]+0.114*a[...,2]

s=est_sigma(luma(blur)); print('echt σ',SIG,' geschätzt σ',round(s,2))
res={}
# A: bisher – KI direkt (×4) dann auf Arbeitsgröße ×1 zurück
res['A KI direkt (bisher)']=resize(ai(blur),W,H)
# B: verkleinern, sodass σ≈1, dann KI ×4, auf Arbeitsgröße
f=min(1,1.0/s); bw,bh=max(32,int(W*f)),max(32,int(H*f))
res['B verkleinert + KI']=resize(ai(resize(blur,bw,bh)),W,H)
# C: Entschärfen (RL auf Helligkeit, Farbe mitskaliert) dann KI direkt
Y=luma(blur); Yd=rl(Y,s); ratio=(Yd+1e-3)/(Y+1e-3)
deb=np.clip(blur*ratio[...,None],0,1)
res['C entschärft + KI']=resize(ai(deb),W,H)
# D: Entschärfen + verkleinern (Rest-σ) + KI
res['D entschärft + verkl. + KI']=resize(ai(resize(deb,max(32,int(W*min(1,1.6/s))),max(32,int(H*min(1,1.6/s))))),W,H)
res['entschärft ohne KI']=deb
for k,v in res.items():
  err=np.sqrt(np.mean((v-truth)**2)); print(f'{k:30s} Fehler {err:.4f}')
fnt=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',22)
rows=[('Wahrheit',truth),('Kamera (unscharf)',blur)]+list(res.items())
c=Image.new('RGB',(W,(H//2+6)*len(rows)),'white')
for i,(n,a) in enumerate(rows):
  t=Image.fromarray((np.clip(a,0,1)*255).astype(np.uint8)).crop((0,40,W,40+H//2)); ImageDraw.Draw(t).text((8,H//2-30),n,font=fnt,fill=(255,0,0)); c.paste(t,(0,i*(H//2+6)))
c.save(f'cmp_{int(SIG)}_{"strong" if direct else "fast"}.jpg',quality=88)
