import numpy as np, onnxruntime as ort, sys
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import gaussian_filter, convolve
rng=np.random.default_rng(5)
W,H=720,480
img=Image.new('RGB',(W,H),(200,178,148)); d=ImageDraw.Draw(img)
fb=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',110)
fs=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',26)
fk=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',20)
d.text((40,20),'DREAME',font=fb,fill=(55,48,40))
d.rectangle([230,170,470,460],fill=(232,110,80)); d.rectangle([250,190,450,440],outline=(40,35,45),width=8)
d.rectangle([254,194,446,232],fill=(40,35,45)); d.text((290,198),'CAUTION',font=fs,fill=(232,110,80))
d.rectangle([254,402,446,436],fill=(40,35,45)); d.text((305,404),'HEAVY',font=fs,fill=(232,110,80))
d.polygon([(300,380),(400,380),(375,280),(325,280)],fill=(40,35,45)); d.ellipse([330,250,370,290],fill=(232,110,80)); d.text((322,330),'+15kg',font=fk,fill=(232,110,80))
t=np.asarray(img).astype(np.float32)/255
# Pappstruktur (feine Fasern)
tex=gaussian_filter(rng.normal(0,1,(H,W)),0.7)*0.035
t=np.clip(t+tex[...,None],0,1)
truth=t
SIG=float(sys.argv[1]) if len(sys.argv)>1 else 2.5
NOISE=0.004   # Mehrbild: wenig Rauschen
blur=np.stack([gaussian_filter(t[...,c],SIG) for c in range(3)],-1)+rng.normal(0,NOISE,(H,W,3))
blur=np.clip(blur,0,1)
np.save('truth.npy',truth); np.save('blur.npy',blur)
print('ok',SIG)
