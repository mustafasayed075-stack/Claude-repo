"""Rebuilds app/src/main/assets/models/nudenet_320n_fp16.tflite from NudeNet's 320n.onnx
(third_party/nudenet/README.md) and checks it against the ONNX model.

Steps: fix the input to 1x3x320x320 (onnxsim), cut the graph before YOLOv8's dist2bbox
(onnx2tf converts that part wrongly), convert with onnx2tf (float32 + float16), then compare
boxes (decoded as BodyPartDecoder does) and class scores with the ONNX model.

Needs two environments, because onnx2tf's TensorFlow pins an older ml_dtypes than onnxsim:
  A: pip install nudenet==3.4.2 onnx onnxsim onnxruntime opencv-python-headless ai-edge-litert pillow
  B: pip install "tensorflow==2.17.*" "tf_keras==2.17.*" onnx==1.16.2 onnx2tf==1.26.3 onnx_graphsurgeon sng4onnx psutil
Usage (env A):  python tools/convert_nudenet.py <work dir> <python of env B> [check images dir]
"""
import glob
import os
import shutil
import subprocess
import sys

import cv2
import numpy as np
import onnx
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "models", "nudenet_320n_fp16.tflite")
EXPOSED = [2, 3, 4, 6, 14]  # see SecondStage.EXPOSED (MALE_BREAST_EXPOSED, 5, deliberately not)

# onnx2tf downloads sample images for its own checks; give it local data instead.
CONVERT = """
import sys, numpy as np
import onnx2tf.utils.common_functions as cf
fake = lambda: np.random.default_rng(0).random((20, 128, 128, 3), dtype=np.float32)
cf.download_test_image_data = fake
import onnx2tf.onnx2tf as o2t
if hasattr(o2t, "download_test_image_data"): o2t.download_test_image_data = fake
import onnx2tf
onnx2tf.convert(input_onnx_file_path=sys.argv[1], output_folder_path=sys.argv[2], non_verbose=True, output_signaturedefs=True)
"""


def anchors():
    ax, ay, st = [], [], []
    for s, g in ((8, 40), (16, 20), (32, 10)):
        for y in range(g):
            for x in range(g):
                ax.append(x + .5); ay.append(y + .5); st.append(s)
    return map(np.array, (ax, ay, st))


def prep(img):  # NudeNet: pad right/bottom to a square, resize to 320, RGB / 255
    a = np.asarray(img.convert("RGB")); h, w = a.shape[:2]; s = max(h, w)
    pad = np.zeros((s, s, 3), np.uint8); pad[:h, :w] = a
    return cv2.resize(pad, (320, 320), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255.0


def main():
    work, py_b = sys.argv[1], sys.argv[2]
    images = sys.argv[3] if len(sys.argv) > 3 else None
    os.makedirs(work, exist_ok=True)
    import nudenet
    src = os.path.join(os.path.dirname(nudenet.__file__), "320n.onnx")
    fixed, cut = os.path.join(work, "320n_fixed.onnx"), os.path.join(work, "320n_dfl.onnx")
    subprocess.run([sys.executable, "-m", "onnxsim", src, fixed, "--overwrite-input-shape", "images:1,3,320,320"], check=True)
    onnx.utils.extract_model(fixed, cut, ["images"], ["/model.22/dfl/Reshape_1_output_0", "/model.22/Sigmoid_output_0"])
    script = os.path.join(work, "convert_b.py")
    open(script, "w").write(CONVERT)
    tfl = os.path.join(work, "tfl")
    shutil.rmtree(tfl, ignore_errors=True)
    subprocess.run([py_b, script, cut, tfl], check=True)
    built = os.path.join(tfl, "320n_dfl_float16.tflite")
    shutil.copyfile(built, OUT)
    print("wrote", OUT)
    if not images:
        return
    import onnxruntime as ort
    sess = ort.InferenceSession(src)
    it = Interpreter(model_path=OUT, num_threads=2); it.allocate_tensors()
    ins, outs = it.get_input_details(), it.get_output_details()
    ax, ay, st = anchors()
    wb = wc = 0.0; flips = n = 0
    for f in sorted(glob.glob(os.path.join(images, "*.jpg")) + glob.glob(os.path.join(images, "*.png"))):
        x = prep(Image.open(f)); ref = sess.run(None, {"images": x.transpose(2, 0, 1)[None]})[0][0]
        it.set_tensor(ins[0]["index"], x[None]); it.invoke()
        got = {tuple(o["shape"].tolist()): it.get_tensor(o["index"])[0] for o in outs}
        l, t, r, b = got[(1, 4, 2100)]; cls = got[(1, 18, 2100)]
        x1, y1, x2, y2 = ax - l, ay - t, ax + r, ay + b
        boxes = np.stack([(x1 + x2) / 2 * st, (y1 + y2) / 2 * st, (x2 - x1) * st, (y2 - y1) * st])
        m = ref[4:].max(0) > 0.2
        if m.any():
            wb = max(wb, float(np.abs(boxes[:, m] - ref[:4, m]).max()))
        wc = max(wc, float(np.abs(cls - ref[4:]).max())); n += 1
        flips += int((ref[4:][EXPOSED].max() >= .45) != (cls[EXPOSED].max() >= .45))
    print(f"check: {n} images, max box diff {wb:.3f} px, max score diff {wc:.5f}, decision flips at 0.45: {flips}")


if __name__ == "__main__":
    main()
