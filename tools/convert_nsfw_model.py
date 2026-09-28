"""Builds the bundled Stage 3 image model: viddexa/nsfw-detection-2-nano → TFLite.

Downloads the original weights (Apache-2.0, pinned revision), applies the
output-preserving graph changes listed in third_party/viddexa_nsfw_detection_2_nano/README.md,
converts with LiteRT-Torch, then checks the TFLite output against the original
PyTorch model on the same input pixels and prints the file's SHA-256.

Input contract of the result: 1x224x224x3 float32 RGB in 0..1 (the app resizes
with nearest-neighbour). Output: softmax over safe, hentai, porn, sexy, drawing.

Usage (scratch virtualenv):
    pip install litert-torch transformers safetensors pillow numpy ai-edge-litert \
        --extra-index-url https://download.pytorch.org/whl/cpu
    python tools/convert_nsfw_model.py [out.tflite] [dir of sample .jpg images]
"""
import glob
import hashlib
import sys

import numpy as np
import torch
from PIL import Image
from transformers import AutoModelForImageClassification

import litert_torch
from ai_edge_litert.interpreter import Interpreter

REPO = "viddexa/nsfw-detection-2-nano"
REVISION = "12e57200346246b37382f746e4d94d10b014f6a1"
MEAN = [0.485, 0.456, 0.406]
STD = [0.47853944, 0.4732864, 0.47434163]
LABELS = ["safe", "hentai", "porn", "sexy", "drawing"]


class FullWindowAvgPool(torch.nn.Module):
    """Global average pool as one full-window AVERAGE_POOL_2D (XNNPACK-accelerated)."""

    def forward(self, x):
        return torch.nn.functional.avg_pool2d(x, kernel_size=(int(x.shape[2]), int(x.shape[3])))


class Wrapped(torch.nn.Module):
    """NHWC RGB 0..1 in, softmax out; the model's (double) normalisation inside the graph."""

    def __init__(self, model):
        super().__init__()
        self.model = model
        self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

    def forward(self, x):
        x = x.permute(0, 3, 1, 2)
        x = ((x - self.mean) / self.std) / self.std  # EfficientNetImageProcessor, include_top=True
        return torch.softmax(self.model(pixel_values=x).logits, -1)


def load():
    model = AutoModelForImageClassification.from_pretrained(REPO, revision=REVISION).eval()
    assert [model.config.id2label[i] for i in range(5)] == LABELS, model.config.id2label
    for module in model.modules():
        if isinstance(getattr(module, "squeeze", None), torch.nn.AdaptiveAvgPool2d):
            module.squeeze = FullWindowAvgPool()
    model.efficientnet.pooler = FullWindowAvgPool()
    return model


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "viddexa_nsfw_detection_2_nano_224.tflite"
    samples = sorted(glob.glob(sys.argv[2] + "/*.jpg"))[:50] if len(sys.argv) > 2 else []
    model = load()
    wrapped = Wrapped(model).eval()
    litert_torch.convert(wrapped, (torch.rand(1, 224, 224, 3),)).export(out)

    it = Interpreter(model_path=out, num_threads=2)
    it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    inputs = [np.asarray(Image.open(f).convert("RGB").resize((224, 224), Image.NEAREST), dtype=np.float32)[None] / 255.0
              for f in samples] or [np.random.rand(1, 224, 224, 3).astype(np.float32) for _ in range(20)]
    worst = 0.0
    for x in inputs:
        it.set_tensor(i["index"], x)
        it.invoke()
        with torch.no_grad():
            ref = wrapped(torch.from_numpy(x)).numpy()[0]
        worst = max(worst, float(np.abs(it.get_tensor(o["index"])[0] - ref).max()))
    sha = hashlib.sha256(open(out, "rb").read()).hexdigest()
    print(f"{out}: max |TFLite - PyTorch| over {len(inputs)} inputs = {worst:.7f}; sha256 {sha}")


if __name__ == "__main__":
    main()
