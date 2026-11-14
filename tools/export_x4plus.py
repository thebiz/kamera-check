"""
Wandelt Real-ESRGAN RealESRGAN_x4plus (.pth, RRDB-Netz) ohne PyTorch in ONNX um.
Gewichte werden als fp16 gespeichert (halbe Dateigröße) und beim Laden nach fp32 gecastet.

Eingang:  input  [1, 3, T, T]    RGB 0..1
Ausgang:  output [1, 3, 4T, 4T]  RGB 0..1 (direkt, ohne Pixel-Shuffle)

Aufruf: python3 export_x4plus.py RealESRGAN_x4plus.pth sr_strong.onnx [T]
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from export_model import load_pth  # noqa: E402


class G:
    def __init__(self, sd):
        self.sd = sd
        self.nodes = []
        self.inits = []
        self.n = 0
        self.consts = {}

    def name(self, p="t"):
        self.n += 1
        return f"{p}{self.n}"

    def weight(self, key):
        """fp16-Initializer + Cast nach fp32."""
        arr = self.sd[key].astype(np.float16)
        raw = key.replace(".", "_") + "_h"
        self.inits.append(numpy_helper.from_array(arr, raw))
        out = key.replace(".", "_")
        self.nodes.append(helper.make_node("Cast", [raw], [out], to=TensorProto.FLOAT))
        return out

    def const(self, value):
        if value not in self.consts:
            nm = f"c_{len(self.consts)}"
            self.inits.append(numpy_helper.from_array(np.array(value, dtype=np.float32), nm))
            self.consts[value] = nm
        return self.consts[value]

    def conv(self, x, prefix):
        y = self.name()
        self.nodes.append(helper.make_node(
            "Conv", [x, self.weight(prefix + ".weight"), self.weight(prefix + ".bias")], [y],
            kernel_shape=[3, 3], pads=[1, 1, 1, 1]))
        return y

    def lrelu(self, x):
        y = self.name()
        self.nodes.append(helper.make_node("LeakyRelu", [x], [y], alpha=0.2))
        return y

    def cat(self, xs):
        y = self.name()
        self.nodes.append(helper.make_node("Concat", xs, [y], axis=1))
        return y

    def scale_add(self, x, s, res):
        a = self.name()
        self.nodes.append(helper.make_node("Mul", [x, self.const(s)], [a]))
        y = self.name()
        self.nodes.append(helper.make_node("Add", [a, res], [y]))
        return y

    def add(self, a, b):
        y = self.name()
        self.nodes.append(helper.make_node("Add", [a, b], [y]))
        return y

    def up2(self, x):
        if "scales_up2" not in self.consts:
            self.inits.append(numpy_helper.from_array(np.array([1, 1, 2, 2], dtype=np.float32), "scales_up2"))
            self.inits.append(numpy_helper.from_array(np.array([], dtype=np.float32), "roi_empty"))
            self.consts["scales_up2"] = "scales_up2"
        y = self.name()
        self.nodes.append(helper.make_node("Resize", [x, "roi_empty", "scales_up2"], [y], mode="nearest",
                                           coordinate_transformation_mode="asymmetric", nearest_mode="floor"))
        return y

    def rdb(self, x, p):
        x1 = self.lrelu(self.conv(x, p + ".conv1"))
        x2 = self.lrelu(self.conv(self.cat([x, x1]), p + ".conv2"))
        x3 = self.lrelu(self.conv(self.cat([x, x1, x2]), p + ".conv3"))
        x4 = self.lrelu(self.conv(self.cat([x, x1, x2, x3]), p + ".conv4"))
        x5 = self.conv(self.cat([x, x1, x2, x3, x4]), p + ".conv5")
        return self.scale_add(x5, 0.2, x)

    def rrdb(self, x, p):
        o = self.rdb(x, p + ".rdb1")
        o = self.rdb(o, p + ".rdb2")
        o = self.rdb(o, p + ".rdb3")
        return self.scale_add(o, 0.2, x)


def build(sd, tile):
    g = G(sd)
    nblocks = len({k.split(".")[1] for k in sd if k.startswith("body.")})
    feat = g.conv("input", "conv_first")
    b = feat
    for i in range(nblocks):
        b = g.rrdb(b, f"body.{i}")
    feat = g.add(feat, g.conv(b, "conv_body"))
    feat = g.lrelu(g.conv(g.up2(feat), "conv_up1"))
    feat = g.lrelu(g.conv(g.up2(feat), "conv_up2"))
    out = g.conv(g.lrelu(g.conv(feat, "conv_hr")), "conv_last")
    g.nodes.append(helper.make_node("Identity", [out], ["output"]))
    graph = helper.make_graph(
        g.nodes, "realesrgan_x4plus",
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 3, tile, tile])],
        [helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 3, tile * 4, tile * 4])],
        g.inits,
    )
    m = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    m.ir_version = 8
    onnx.checker.check_model(m)
    return m, nblocks


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    tile = int(sys.argv[3]) if len(sys.argv) > 3 else 128
    sd = load_pth(src)
    m, nb = build(sd, tile)
    onnx.save(m, dst)
    print("ok", dst, nb, "RRDB-Blöcke")
