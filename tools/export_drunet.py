"""
DRUNet (Zhang et al., „Plug-and-Play Image Restoration with Deep Denoiser Prior“, DPIR, 2021)
ohne PyTorch nach ONNX. Graustufen-Modell, Gewichte als fp16 (halbe Größe), beim Laden → fp32.

Eingang:  x  [1, 2, H, W]  Kanal 0 = Bild 0..1, Kanal 1 = Rauschstärke σ (0..1) überall gleich
          feste Größe 256 × 256 (der KI-Chip/NNAPI braucht feste Größen; das Bild wird gekachelt)
Ausgang:  y  [1, 1, H, W]  entrauschtes Bild

Aufruf: python3 export_drunet.py drunet_gray.pth drunet_gray.onnx
Quelle der Gewichte: https://github.com/cszn/KAIR (MIT-Lizenz)
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from legacy_pth import load_legacy  # noqa: E402


T = 256


def build(sd):
    nodes, inits = [], []
    cnt = [0]

    def nm(p="t"):
        cnt[0] += 1
        return f"{p}{cnt[0]}"

    def w(key):
        raw = key.replace(".", "_") + "_h"
        inits.append(numpy_helper.from_array(sd[key].astype(np.float16), raw))
        out = key.replace(".", "_")
        nodes.append(helper.make_node("Cast", [raw], [out], to=TensorProto.FLOAT))
        return out

    def conv(x, key, k=3, stride=1):
        o = nm()
        pad = (k - 1) // 2 if stride == 1 else 0
        nodes.append(helper.make_node("Conv", [x, w(key)], [o], kernel_shape=[k, k], strides=[stride, stride], pads=[pad] * 4))
        return o

    def convT(x, key):
        o = nm()
        nodes.append(helper.make_node("ConvTranspose", [x, w(key)], [o], kernel_shape=[2, 2], strides=[2, 2]))
        return o

    def relu(x):
        o = nm()
        nodes.append(helper.make_node("Relu", [x], [o]))
        return o

    def add(a, b):
        o = nm()
        nodes.append(helper.make_node("Add", [a, b], [o]))
        return o

    def res(x, prefix):
        r = conv(relu(conv(x, prefix + ".res.0.weight")), prefix + ".res.2.weight")
        return add(x, r)

    x1 = conv("x", "m_head.weight")
    h = x1
    for i in range(4):
        h = res(h, f"m_down1.{i}")
    x2 = conv(h, "m_down1.4.weight", k=2, stride=2)
    h = x2
    for i in range(4):
        h = res(h, f"m_down2.{i}")
    x3 = conv(h, "m_down2.4.weight", k=2, stride=2)
    h = x3
    for i in range(4):
        h = res(h, f"m_down3.{i}")
    x4 = conv(h, "m_down3.4.weight", k=2, stride=2)
    h = x4
    for i in range(4):
        h = res(h, f"m_body.{i}")
    h = convT(add(h, x4), "m_up3.0.weight")
    for i in range(1, 5):
        h = res(h, f"m_up3.{i}")
    h = convT(add(h, x3), "m_up2.0.weight")
    for i in range(1, 5):
        h = res(h, f"m_up2.{i}")
    h = convT(add(h, x2), "m_up1.0.weight")
    for i in range(1, 5):
        h = res(h, f"m_up1.{i}")
    o = conv(add(h, x1), "m_tail.weight")
    nodes.append(helper.make_node("Identity", [o], ["y"]))

    g = helper.make_graph(
        nodes, "drunet",
        [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 2, T, T])],
        [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 1, T, T])],
        inits,
    )
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)])
    m.ir_version = 8
    return m


if __name__ == "__main__":
    sd = load_legacy(sys.argv[1])
    m = build(sd)
    onnx.checker.check_model(m)
    onnx.save(m, sys.argv[2])
    print("ok", sys.argv[2])
