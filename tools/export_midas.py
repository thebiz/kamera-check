"""
Tiefen-KI für „Plastizität“: MiDaS v2.1 small (Ranftl et al., MIT-Lizenz, github.com/isl-org/MiDaS).
Original-ONNX (model-small.onnx, Release v2_1) → Gewichte als fp16 (halbe Größe), beim Laden per Cast → fp32.
Eingang 1×3×256×256 (RGB, ImageNet-normiert), Ausgang 1×256×256 inverse relative Tiefe (groß = nah).
Aufruf: python3 tools/export_midas.py model-small.onnx app/src/main/assets/midas256.onnx
"""
import sys
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
new_inits, casts = [], []
for init in g.initializer:
    a = numpy_helper.to_array(init)
    if a.dtype == np.float32 and a.size > 16:
        h = init.name + "_fp16"
        new_inits.append(numpy_helper.from_array(a.astype(np.float16), h))
        casts.append(helper.make_node("Cast", [h], [init.name], to=TensorProto.FLOAT))
    else:
        new_inits.append(init)
del g.initializer[:]
g.initializer.extend(new_inits)
nodes = list(g.node)
del g.node[:]
g.node.extend(casts + nodes)
onnx.checker.check_model(m)
onnx.save(m, dst)
