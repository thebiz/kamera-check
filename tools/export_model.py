"""
Wandelt Real-ESRGAN realesr-general-x4v3 (.pth) ohne PyTorch in ein ONNX-Modell um.

Das Modell enthält nur Faltungen + PReLU (gut für Handy-Beschleuniger).
Pixel-Shuffle (×4) und die Basis-Addition (Nearest-Upscale des Eingangs)
macht die App selbst.

Eingang:  input  [1, 3, T, T]   RGB 0..1
Ausgang:  output [1, 48, T, T]  → per Pixel-Shuffle zu [3, 4T, 4T]

Aufruf: python3 export_model.py realesr-general-x4v3.pth sr.onnx [T] [realesr-general-wdn-x4v3.pth ANTEIL]

Mit dem wdn-Modell und ANTEIL (0..1) werden die Gewichte gemischt wie bei
Real-ESRGANs "denoise strength": 1 = glatt, 0 = natürlich. Die App nutzt 0,5.
"""
import pickle
import sys
import zipfile

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


# ── .pth ohne torch lesen ──────────────────────────────────────────────

class _Storage:
    def __init__(self, dtype, key):
        self.dtype, self.key = dtype, key


def load_pth(path):
    zf = zipfile.ZipFile(path)
    prefix = zf.namelist()[0].split("/")[0]
    dtypes = {"FloatStorage": np.float32, "HalfStorage": np.float16, "LongStorage": np.int64}

    def rebuild(storage, offset, size, stride, *args):
        raw = zf.read(f"{prefix}/data/{storage.key}")
        flat = np.frombuffer(raw, dtype=storage.dtype)
        n = int(np.prod(size)) if len(size) else 1
        arr = np.lib.stride_tricks.as_strided(
            flat[offset:], shape=size,
            strides=[s * flat.itemsize for s in stride],
        ) if len(size) else flat[offset:offset + 1].reshape(())
        return np.array(arr, dtype=np.float32, copy=True).reshape(size) if n else arr

    class U(pickle.Unpickler):
        def find_class(self, mod, name):
            if name == "_rebuild_tensor_v2":
                return rebuild
            if name in dtypes:
                return name
            if mod == "collections" and name == "OrderedDict":
                import collections
                return collections.OrderedDict
            return super().find_class(mod, name)

        def persistent_load(self, pid):
            _, storage_type, key, _loc, _n = pid
            return _Storage(dtypes[storage_type], key)

    with zf.open(f"{prefix}/data.pkl") as f:
        obj = U(f).load()
    return obj.get("params_ema") or obj.get("params") or obj


# ── ONNX bauen ─────────────────────────────────────────────────────────

def build(sd, tile):
    keys = sorted({int(k.split(".")[1]) for k in sd}, key=int)
    nodes, inits = [], []
    cur = "input"
    for i in keys:
        w = sd.get(f"body.{i}.weight")
        b = sd.get(f"body.{i}.bias")
        out = f"t{i}"
        if w is not None and w.ndim == 4:
            inits += [numpy_helper.from_array(w.astype(np.float32), f"w{i}"),
                      numpy_helper.from_array(b.astype(np.float32), f"b{i}")]
            nodes.append(helper.make_node("Conv", [cur, f"w{i}", f"b{i}"], [out],
                                          kernel_shape=[3, 3], pads=[1, 1, 1, 1]))
        else:  # PReLU, Gewicht pro Kanal
            slope = w.reshape(-1, 1, 1).astype(np.float32)
            inits.append(numpy_helper.from_array(slope, f"p{i}"))
            nodes.append(helper.make_node("PRelu", [cur, f"p{i}"], [out]))
        cur = out
    nodes.append(helper.make_node("Identity", [cur], ["output"]))
    last_out = sd[f"body.{keys[-1]}.weight"].shape[0]
    g = helper.make_graph(
        nodes, "realesr_general_x4v3",
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 3, tile, tile])],
        [helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, last_out, tile, tile])],
        inits,
    )
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)])
    m.ir_version = 8
    onnx.checker.check_model(m)
    return m


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    tile = int(sys.argv[3]) if len(sys.argv) > 3 else 128
    sd = load_pth(src)
    if len(sys.argv) > 5:
        wdn = load_pth(sys.argv[4])
        a = float(sys.argv[5])
        sd = {k: a * sd[k] + (1 - a) * wdn[k] for k in sd}
    onnx.save(build(sd, tile), dst)
    print("ok", dst, len(sd), "Tensoren")
