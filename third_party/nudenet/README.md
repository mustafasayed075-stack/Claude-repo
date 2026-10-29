# NudeNet v3 `320n` (bundled as TFLite) — second-stage reviewer

Guardian's second stage (README "Second stage") is NudeNet's body-part detector,
converted to TensorFlow Lite. It runs only when the first stage (viddexa-nano)
decides to lock, and says whether the frame actually shows an exposed body part.
In Build 48 it runs in **shadow mode**: it logs what it would decide, and the
locks are unchanged.

| | |
|---|---|
| Model | NudeNet v3 `320n` — Ultralytics YOLOv8n detector, 320×320 input, 18 classes (`class_labels.txt`, NudeNet's order) |
| Source | https://github.com/notAI-tech/NudeNet (v3), weights `320n.onnx` as shipped in the `nudenet` 3.4.2 Python package |
| Original weights | `320n.onnx`, 12,150,158 bytes, SHA-256 `c15d8273adad2d0a92f014cc69ab2d6c311a06777a55545f2c4eb46f51911f0f` |
| Licence | **GNU AGPL-3.0** (the repository's `LICENSE`; full text in `LICENSE-AGPL-3.0.txt`). The PyPI metadata says MIT, and the model is built on Ultralytics YOLOv8 (AGPL-3.0); this project treats it as AGPL-3.0 and is itself AGPL-3.0 (root `LICENSE`). |

## Changes made

The bundled file `app/src/main/assets/models/nudenet_320n_fp16.tflite`
(6,084,680 bytes, SHA-256 `ce1befc5d38137a1cf5a5dab4964c79aca688c8ff3ba4d0aee83b7fac8be9c52`)
was produced from `320n.onnx` by `tools/convert_nudenet.py`. The learned weights are
unchanged apart from being stored as float16; the graph was changed as follows:

1. **Static input** `1×320×320×3` float32 RGB in 0..1 (NHWC). NudeNet's own
   pre-processing is kept in the app: pad the image to a square with black on the
   right/bottom, then one bilinear resize to 320.
2. **Box decode moved out of the graph.** The graph is cut before YOLOv8's
   `dist2bbox`, which onnx2tf converted wrongly (boxes on the right-edge anchors were
   off by ~600 px). Outputs: `[1,4,2100]` DFL distances (left, top, right, bottom in
   grid units) and `[1,18,2100]` class scores (sigmoid). `BodyPartDecoder` (Kotlin)
   adds the anchors (strides 8/16/32 on 40/20/10 grids, centres +0.5) and NudeNet's
   post-processing: keep anchors whose best class ≥ 0.2, then class-agnostic NMS
   (score 0.25, IoU 0.45), as `nudenet.py` does with `cv2.dnn.NMSBoxes`.

Conversion check (133 images: COCO people photos and the TikTok regression frames),
against the original ONNX model on the same input pixels: boxes within 0.10 px
(320 space), class scores within 0.0102, no "exposed ≥ 0.45" decision changed.
float32 conversion: boxes within 0.001 px, scores within 0.00001.
