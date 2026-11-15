import numpy as np, time, onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import gaussian_filter, minimum_filter, maximum_filter

rng = np.random.default_rng(1)
W = H = 512
img = Image.new("L", (W, H), 225)
d = ImageDraw.Draw(img)
small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf", 11)
big = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf", 48)
txt = "Zutaten: 100% Erdnüsse, geröstet. Kann Spuren von Schalen enthalten. Kühl lagern."
for i in range(16):
    d.text((14, 12 + i * 15), txt[(i * 7) % 40:] + " " + txt, fill=60, font=small)
d.text((60, 300), "500 g", fill=30, font=big)
d.ellipse((330, 300, 470, 400), outline=40, width=3)
clean = np.asarray(img, np.float32) / 255

S = 1.2
def psnr(a): return 10 * np.log10(1 / np.mean((np.clip(a, 0, 1) - clean)[16:-16, 16:-16] ** 2))

def gk(s, size=15):
    x = np.arange(size) - size // 2
    g = np.exp(-x**2 / (2 * s * s)); g /= g.sum()
    return np.outer(g, g)

def blur(x, s): return gaussian_filter(x, s, mode="reflect")

def rl(y, s, it=80):
    x = y.copy()
    for _ in range(it):
        x = x * blur(y / np.maximum(blur(x, s), 1e-4), s)
    return x

def tame(y, yd, soft=0.15, up=0.1, down=0.6):
    lo = minimum_filter(y, 5); hi = maximum_filter(y, 5); r = hi - lo
    v = y + soft * np.tanh((yd - y) / soft)
    return np.clip(v, lo - down * r - 0.01, hi + up * r + 0.01)

sess = ort.InferenceSession("/tmp/dpir/drunet_gray.onnx", providers=["CPUExecutionProvider"])
def den(x, sig):
    inp = np.stack([x, np.full_like(x, sig)])[None].astype(np.float32)
    return sess.run(None, {"x": inp})[0][0, 0]

def dpir(y, s, noise, iters=8, lam=0.23):
    # Rand spiegeln, damit die FFT keine Kanten-Artefakte bringt
    p = 32
    yp = np.pad(y, p, mode="symmetric")
    h, w = yp.shape
    k = np.zeros((h, w), np.float32); kk = gk(s, 15)
    k[:15, :15] = kk; k = np.roll(k, (-7, -7), (0, 1))
    FB = np.fft.fft2(k); FBC = np.conj(FB); F2B = np.abs(FB) ** 2
    FBFy = FBC * np.fft.fft2(yp)
    sig1, sig2 = 49 / 255, max(noise, 0.255 / 255)
    sigmas = np.logspace(np.log10(sig1), np.log10(sig2), iters)
    rhos = lam * noise ** 2 / sigmas ** 2
    x = yp.copy()
    for i in range(iters):
        x = np.real(np.fft.ifft2((FBFy + rhos[i] * np.fft.fft2(x)) / (F2B + rhos[i])))
        x = den(x.astype(np.float32), sigmas[i])
    return x[p:-p, p:-p]

out = {}
for noise in []:
    y = blur(clean, S) + rng.normal(0, noise, clean.shape).astype(np.float32)
    r = rl(y, S); t = tame(y, r)
    t0 = time.time(); dp = dpir(y, S, noise); dt = time.time() - t0
    dp4 = dpir(y, S, noise, iters=4)
    print(f"Rauschen {noise*255:.0f}/255: unscharf {psnr(y):.2f} dB · RL {psnr(r):.2f} · RL gebremst {psnr(t):.2f} · DPIR-8 {psnr(dp):.2f} ({dt:.1f}s) · DPIR-4 {psnr(dp4):.2f}")
    out[noise] = (y, t, dp)

import sys
crop = lambda a: (np.clip(a, 0, 1)[0:200, 0:300] * 255).astype(np.uint8)
#row = ([crop(clean), np.full((200, 6), 255, np.uint8), crop(y), np.full((200, 6), 255, np.uint8), crop(t), np.full((200, 6), 255, np.uint8), crop(dp)], 1)
#Image.resize((row.shape[1] * 2, 400), Image.NEAREST).save("/tmp/dpir/vergleich.png")

print("--- Durchgänge (Rauschen 2/255) ---")
y = blur(clean, S) + rng.normal(0, 2 / 255, clean.shape).astype(np.float32)
for it in [1, 2, 3, 4]:
    print(it, f"{psnr(dpir(y, S, 2/255, iters=it)):.2f} dB")
# Kacheln 256 (Rand 24) wie auf dem Handy
def dpir_tiled(y, s, noise, iters, T=256, P=24):
    hh, ww = y.shape; inner = T - 2 * P; out = np.zeros_like(y)
    ypad = np.pad(y, P, mode="symmetric")
    for ty in range(0, hh, inner):
        for tx in range(0, ww, inner):
            t = ypad[ty:ty + T, tx:tx + T]
            if t.shape != (T, T): t = np.pad(t, ((0, T - t.shape[0]), (0, T - t.shape[1])), mode="symmetric")
            # ohne eigenes Spiegeln: dpir() polstert selbst; hier Polster = echte Nachbarn
            r = dpir(t, s, noise, iters)
            hb = min(inner, hh - ty); wb = min(inner, ww - tx)
            out[ty:ty + hb, tx:tx + wb] = r[P:P + hb, P:P + wb]
    return out
print("Kacheln 3 Durchgänge", f"{psnr(dpir_tiled(y, S, 2/255, 3)):.2f} dB")

def dpir_nopad(y, s, noise, iters=3, lam=0.23):
    h, w = y.shape
    k = np.zeros((h, w), np.float32); k[:15, :15] = gk(s, 15); k = np.roll(k, (-7, -7), (0, 1))
    FB = np.fft.fft2(k); F2B = np.abs(FB) ** 2; FBFy = np.conj(FB) * np.fft.fft2(y)
    sigmas = np.logspace(np.log10(49/255), np.log10(max(noise, 0.255/255)), iters); rhos = lam * noise**2 / sigmas**2
    x = y.copy()
    for i in range(iters):
        x = np.real(np.fft.ifft2((FBFy + rhos[i] * np.fft.fft2(x)) / (F2B + rhos[i])))
        x = den(x.astype(np.float32), sigmas[i])
    return x
dpir = dpir_nopad
print("Kacheln ohne Extra-Rand, 3 Durchgänge", f"{psnr(dpir_tiled(y, S, 2/255, 3)):.2f} dB")
