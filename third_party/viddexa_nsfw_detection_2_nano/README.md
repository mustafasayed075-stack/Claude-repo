# viddexa/nsfw-detection-2-nano (bundled as TFLite)

Guardian's on-device image classifier (Stage 3) is this model, converted to
TensorFlow Lite.

| | |
|---|---|
| Model | `viddexa/nsfw-detection-2-nano` — EfficientNet-B0 (4.0M parameters) fine-tuned into 5 classes: safe, hentai, porn, sexy, drawing |
| Authors | Kerem Bozgan, Abdullah Kırman, Fatih Çağatay Akyön (viddexa), based on F. C. Akyon's work (arXiv:2312.16338) |
| Source | https://huggingface.co/viddexa/nsfw-detection-2-nano, revision `12e57200346246b37382f746e4d94d10b014f6a1` |
| Original weights | `model.safetensors`, SHA-256 `011ef883033b5908994a06d3b6dcfbf55498206afc1cb55849f918588c7dfcba` |
| Base model | `google/efficientnet-b0` (Apache-2.0) |
| Licence | **Apache License 2.0** — full text in `LICENSE-Apache-2.0.txt` |
| Published scores (LSPD test set, per the model card) | F1: safe 96.82%, porn 96.34%, hentai 93.43%, drawing 93.24%, sexy 85.15% (macro 93.00%) |

## Changes made (Apache-2.0 §4(b))

The bundled file `app/src/main/assets/models/viddexa_nsfw_detection_2_nano_224.tflite`
(SHA-256 `87b4701b8a69d771b90d635ef5145df8c92e1c4632da47b270029a15ef4b7fa2`)
was produced from the original weights by `tools/convert_nsfw_model.py`, which
rebuilds it byte-for-byte. The learned weights are unchanged; the graph was
changed as follows, without changing its outputs:

1. **Input contract:** `1×224×224×3` float32 RGB in 0..1 (NHWC), the same as the
   previously bundled model. The model's normalisation is part of the graph:
   `((x − mean) / std) / std` with mean (0.485, 0.456, 0.406) and std
   (0.47853944, 0.4732864, 0.47434163). This is what Hugging Face's
   `EfficientNetImageProcessor` does with `include_top = true`.
2. **Output:** softmax over the 5 classes, in the order of `class_labels.txt`.
3. **Pooling:** the 16 squeeze-and-excite `AdaptiveAvgPool2d(1)` layers and the
   final `AvgPool2d(1280, ceil_mode=True)` pooler (a global mean on the 7×7 map)
   are expressed as full-window average pools. The originals converted to
   non-accelerated ops (`GATHER_ND`) and a 1280×1280 padded window, which took
   ~1.6 s per image. The results are identical.

Conversion check: the TFLite output matches the original PyTorch model to within
0.000005 (max |difference| of the 5 probabilities over 51 test images, same input
pixels).
