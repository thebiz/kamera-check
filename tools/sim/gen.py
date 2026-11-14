# Simuliert eine verwackelte RAW+YUV-Bildserie eines entfernten Schildes
import numpy as np, struct
from PIL import Image, ImageDraw, ImageFont, ImageFilter
rng=np.random.default_rng(7)
S=8                      # Szene ist 8× feiner als der Sensor
RW,RH=512,384            # Sensor-Ausschnitt mit Rand
N=12
M=40                     # Rand in Sensorpixeln für Verschiebungen
W,H=(RW+2*M)*S,(RH+2*M)*S
img=Image.new('RGB',(W,H),(150,95,75)); d=ImageDraw.Draw(img)
for y in range(0,H,90):
  off=(y//90%2)*110
  for x in range(-220,W,220):
    c=tuple(int(v) for v in rng.integers(110,190,3)*np.array([1,.62,.5]))
    d.rectangle([x+off+8,y+8,x+off+212,y+82],fill=c)
d.rectangle([700,900,3900,2500],fill=(236,232,218),outline=(25,25,25),width=40)
f1=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',380)
f2=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',150)
f3=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',72)
d.text((880,1000),'St. Petri',font=f1,fill=(20,20,20)); d.text((900,1700),'Hamburg · 1195',font=f2,fill=(20,20,20))
d.text((900,2000),'Gottesdienst sonntags 10 Uhr',font=f3,fill=(20,20,20)); d.text((900,2120),'Turmbesteigung 4 Euro · 544 Stufen',font=f3,fill=(20,20,20)); d.text((900,2240),'Konzert Freitag 19:30 Orgel Bach',font=f3,fill=(20,20,20))
img=img.filter(ImageFilter.GaussianBlur(0.55*S))   # Objektiv-Unschärfe
lin=(np.asarray(img).astype(np.float32)/255)**2.2  # linear
black,white=64,1023
def sensor(sx,sy):
  ox=int(round((M+sx)*S)); oy=int(round((M+sy)*S))
  a=lin[oy:oy+RH*S, ox:ox+RW*S]
  return a.reshape(RH,S,RW,S,3).mean(axis=(1,3))   # Pixel integrieren
shifts=[(0,0)]+[tuple(rng.uniform(-3,3,2)) for _ in range(N-1)]
raws=[]; ys=[]
wb=np.array([0.5,1.0,0.7])   # Sensor sieht Farben "roh", WB gleicht aus
for (sx,sy) in shifts:
  s=sensor(sx,sy)*wb          # Sensor-Rohsignal je Farbe
  bay=np.zeros((RH,RW),np.float32)
  bay[0::2,0::2]=s[0::2,0::2,0]; bay[0::2,1::2]=s[0::2,1::2,1]
  bay[1::2,0::2]=s[1::2,0::2,1]; bay[1::2,1::2]=s[1::2,1::2,2]
  sig=bay*(white-black)
  noisy=sig+rng.normal(0,1,sig.shape)*np.sqrt(4+sig*0.6)+black
  raws.append(np.clip(np.round(noisy),0,1023).astype(np.uint16))
  # "Kamera-YUV" realistisch: aus dem eigenen Bayer-Bild demosaikt (bilinear), entrauscht, Gamma,
  # gegenüber RAW um (+1,-2) verschoben
  rb=(raws[-1].astype(np.float32)-black)/(white-black)
  def dem(b):
    from scipy.ndimage import convolve
    R=np.zeros_like(b);G=np.zeros_like(b);B=np.zeros_like(b)
    R[0::2,0::2]=b[0::2,0::2];G[0::2,1::2]=b[0::2,1::2];G[1::2,0::2]=b[1::2,0::2];B[1::2,1::2]=b[1::2,1::2]
    kg=np.array([[0,1,0],[1,4,1],[0,1,0]])/4; kr=np.array([[1,2,1],[2,4,2],[1,2,1]])/4
    return np.stack([convolve(R,kr)/wb[0],convolve(G,kg)/wb[1],convolve(B,kr)/wb[2]],-1)
  rgb=np.roll(np.roll(np.clip(dem(rb),0,1),-1,1),2,0)
  yl=(0.299*rgb[...,0]+0.587*rgb[...,1]+0.114*rgb[...,2])**(1/2.2)*255
  yl=np.asarray(Image.fromarray(np.clip(yl,0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8)))
  ys.append(yl.astype(np.uint8))
# Wahrheit: Szene im Zielausschnitt mit 2× Sensorauflösung
CX,CY,CW,CH=64,64,384,256
gt=lin[(M+CY-2)*S:(M+CY-2+CH)*S,(M+CX+1)*S:(M+CX+1+CW)*S]
gt=gt.reshape(CH*2,S//2,CW*2,S//2,3).mean(axis=(1,3))
gty=(0.299*gt[...,0]+0.587*gt[...,1]+0.114*gt[...,2])**(1/2.2)*255
Image.fromarray(np.clip(gty,0,255).astype(np.uint8)).save('/tmp/simsr/truth.png')
merged=ys[0][CY:CY+CH,CX:CX+CW].astype(np.float32)   # Referenz-YUV im Ausschnitt
with open('/tmp/simsr/data.bin','wb') as f:
  f.write(struct.pack('<6i',RW,RH,N,CX,CY,CW)); f.write(struct.pack('<i',CH))
  for r in raws: f.write(r.astype('<u2').tobytes())
  f.write(ys[0].tobytes()); f.write(merged.astype('<f4').tobytes())
# Vergleich: normales Hochskalieren des YUV-Referenzbildes
Image.fromarray(ys[0][CY:CY+CH,CX:CX+CW]).resize((CW*2,CH*2),Image.BICUBIC).save('/tmp/simsr/bicubic.png')
print('ok', shifts[:3])
