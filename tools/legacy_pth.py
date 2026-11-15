"""Liest alte PyTorch-.pth-Dateien (Format vor 1.6, kein Zip) ohne PyTorch → dict name → numpy."""
import pickle
import struct

import numpy as np

DT = {"FloatStorage": np.float32, "HalfStorage": np.float16, "LongStorage": np.int64, "IntStorage": np.int32}


def load_legacy(path):
    f = open(path, "rb")
    storages = {}

    class S:
        def __init__(self, dtype, key, size):
            self.dtype, self.key, self.size = dtype, key, size

    def rebuild(storage, offset, size, stride, *a):
        return ("T", storage.key, offset, tuple(size), tuple(stride))

    class U(pickle.Unpickler):
        def find_class(self, mod, name):
            if name == "_rebuild_tensor_v2":
                return rebuild
            if name in DT:
                return name
            if mod == "collections" and name == "OrderedDict":
                import collections
                return collections.OrderedDict
            return super().find_class(mod, name)

        def persistent_load(self, pid):
            _, stype, key, _loc, size, _view = pid
            if key not in storages:
                storages[key] = S(DT[stype], key, size)
            return storages[key]

    pickle.load(f)  # magic
    pickle.load(f)  # protocol
    pickle.load(f)  # sys info
    obj = U(f).load()
    keys = pickle.load(f)
    data = {}
    for k in keys:
        n = struct.unpack("<q", f.read(8))[0]
        st = storages[k]
        data[k] = np.frombuffer(f.read(n * np.dtype(st.dtype).itemsize), dtype=st.dtype)
    out = {}
    for name, t in obj.items():
        _, key, off, size, stride = t
        flat = data[key]
        arr = np.lib.stride_tricks.as_strided(flat[off:], shape=size, strides=[s * flat.itemsize for s in stride])
        out[name] = np.array(arr, dtype=np.float32)
    return out
