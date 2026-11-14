import numpy as np, onnxruntime as ort, sys, time
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import gaussian_filter
truth=np.load('truth.npy'); blur=np.load('blur.npy'); H,W,_=truth.shape
SIG=float(sys.argv[1])
luma=lambda a: 0.299*a[...,0]+0.587*a[...,1]+0.114*a[...,2]
def apply_luma(rgb,Y0,Y1): return np.clip(rgb+(Y1-Y0)[...,None],0,1)   # Helligkeitsdetail übertragen, Farbe bleibt

def rl(y,s,it):
    e=y.copy()
    for _ in range(it):
        e=e*gaussian_filter(y/np.maximum(gaussian_filter(e,s),1e-4),s); e=np.clip(e,1e-4,1.5)
    return e
def rl_tv(y,s,it,lam):
    e=y.copy()
    for _ in range(it):
        gy,gx=np.gradient(e); n=np.sqrt(gx**2+gy**2)+1e-3
        div=np.gradient(gx/n,axis=1)+np.gradient(gy/n,axis=0)
        e=e*gaussian_filter(y/np.maximum(gaussian_filter(e,s),1e-4),s)/np.maximum(1-lam*div,0.3)
        e=np.clip(e,1e-4,1.5)
    return e
def wiener(y,s,K):
    fy=np.fft.fftfreq(y.shape[0])[:,None]; fx=np.fft.fftfreq(y.shape[1])[None,:]
    Hf=np.exp(-2*(np.pi**2)*(s**2)*(fx**2+fy**2))
    pad=int(4*s)+4; yp=np.pad(y,pad,mode='reflect')
    fy=np.fft.fftfreq(yp.shape[0])[:,None]; fx=np.fft.fftfreq(yp.shape[1])[None,:]
    Hf=np.exp(-2*(np.pi**2)*(s**2)*(fx**2+fy**2))
    G=np.conj(Hf)/(Hf**2+K)
    return np.real(np.fft.ifft2(np.fft.fft2(yp)*G))[pad:-pad,pad:-pad]

sess=ort.InferenceSession('/home/claude/kamera-check/app/src/main/assets/sr128.onnx')
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
      sr=o.reshape(3,4,4,T,T).transpose(3,1,4,2,0).reshape(T*4,T*4,3)+np.repeat(np.repeat(tile,4,0),4,1)
      vy0,vy1=(0,H) if H<=T else (ty,min(ty+ST,H)); vx0,vx1=(0,W) if W<=T else (tx,min(tx+ST,W))
      out[vy0*4:vy1*4,vx0*4:vx1*4]=sr[(vy0-y0)*4:(vy1-y0)*4,(vx0-x0)*4:(vx1-x0)*4]
  return np.clip(out,0,1)
def resize(a,w,h): return np.asarray(Image.fromarray((np.clip(a,0,1)*255).astype(np.uint8)).resize((w,h),Image.LANCZOS)).astype(np.float32)/255

Y=luma(blur)
res={}
res['Normal (unscharf)']=blur
res['A KI direkt']=resize(ai(blur),W,H)
tgt=min(1.8,max(1.0,0.9+0.12*SIG)); f=min(1,tgt/SIG)
res['B verkleinert + KI (v0.9)']=resize(ai(resize(blur,int(W*f),int(H*f))),W,H)
res['RL']=apply_luma(blur,Y,rl(Y,SIG,25))
for lam in [0.002,0.006]:
    res[f'RL+Kantenschutz λ={lam}']=apply_luma(blur,Y,rl_tv(Y,SIG,40,lam))
for K in [0.002,0.01]:
    res[f'Wiener K={K}']=apply_luma(blur,Y,wiener(Y,SIG,K))
best_tv=res['RL+Kantenschutz λ=0.006']
kiOnTv=resize(ai(best_tv),W,H)
res['RL+Kantenschutz, KI 30 %']=np.clip(0.7*best_tv+0.3*kiOnTv,0,1)
e=lambda a,b: np.sqrt(np.mean((a-b)**2))
st=(slice(170,460),slice(230,470))
for k,v in res.items(): print(f'{k:32s} gesamt {e(v,truth):.4f}  Aufkleber {e(v[st],truth[st]):.4f}')
np.save('res.npy',res,allow_pickle=True)
fnt=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',16)
names=['Normal (unscharf)','A KI direkt','B verkleinert + KI (v0.9)','RL','RL+Kantenschutz λ=0.006','Wiener K=0.01','RL+Kantenschutz, KI 30 %']
rows=[('Wahrheit',truth)]+[(n,res[n]) for n in names]
cw,ch=360,260
c=Image.new('RGB',(cw*4+12,(ch+4)*2),'white')
for i,(n,a) in enumerate(rows):
  t=Image.fromarray((np.clip(a,0,1)*255).astype(np.uint8)).crop((180,170,540,430)); ImageDraw.Draw(t).rectangle([0,ch-22,len(n)*9+10,ch],fill=(255,255,255)); ImageDraw.Draw(t).text((4,ch-20),n,font=fnt,fill=(200,0,0))
  c.paste(t,((i%4)*(cw+4),(i//4)*(ch+4)))
c.save(f'cmp_{SIG}.jpg',quality=90)
