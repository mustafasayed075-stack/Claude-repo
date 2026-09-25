"""Re-verifies the bundled GantMan nsfw_model TFLite file and the app's preprocessing.

Downloads the official release archive, checks that the bundled .tflite is the
file shipped in it, compares it against the release's SavedModel, and prints the
signal (sexy + porn + hentai) on ordinary sample images and phone-screen layouts,
using the same steps as the app (stepwise halving, 224x224, RGB / 255).

Usage (from a scratch virtualenv):
    pip install tensorflow-cpu pillow scikit-image numpy
    python tools/verify_nsfw_model.py
"""
import hashlib
import io
import os
import sys
import urllib.request
import zipfile

import numpy as np
import skimage.data
import tensorflow as tf
from PIL import Image

RELEASE_URL = ("https://github.com/GantMan/nsfw_model/releases/download/1.2.0/"
               "mobilenet_v2_140_224.1.zip")
RELEASE_SHA256 = "22c0892695929639c16ea302996b8f64df9c52e7a6c1d874c1de1047bfe109f7"
BUNDLED = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets",
                       "models", "nsfw_mobilenet_v2_140_224.tflite")
LABELS = ["drawings", "hentai", "neutral", "porn", "sexy"]


def halve_to(im: Image.Image, target: int = 224) -> Image.Image:
    """Mirror of NsfwPreprocessor.downscaleSteps + filtered scaling on Android."""
    w, h = im.size
    while w > 2 * target or h > 2 * target:
        w2 = w // 2 if w > 2 * target else w
        h2 = h // 2 if h > 2 * target else h
        im = im.reduce((w // w2, h // h2))
        w, h = im.size
    return im.resize((target, target), Image.BILINEAR)


def phone_screen(photo: Image.Image) -> Image.Image:
    screen = Image.new("RGB", (1080, 2400), (250, 250, 250))
    h = int(photo.height * 1080 / photo.width)
    screen.paste(photo.resize((1080, h)), (0, (2400 - h) // 2))
    return screen


def main() -> int:
    data = urllib.request.urlopen(RELEASE_URL).read()
    assert hashlib.sha256(data).hexdigest() == RELEASE_SHA256, "release archive changed"
    zf = zipfile.ZipFile(io.BytesIO(data))
    release_tflite = zf.read("mobilenet_v2_140_224/saved_model.tflite")
    bundled = open(BUNDLED, "rb").read()
    assert bundled == release_tflite, "bundled model differs from the release file"
    print("bundled .tflite == release saved_model.tflite:",
          hashlib.sha256(bundled).hexdigest())

    tmp = "/tmp/nsfw_model_release"
    zf.extractall(tmp)
    reference = tf.saved_model.load(os.path.join(tmp, "mobilenet_v2_140_224")).signatures["serving_default"]
    interp = tf.lite.Interpreter(model_content=bundled)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0]["index"], interp.get_output_details()[0]["index"]

    def classify(im):
        x = (np.asarray(halve_to(im.convert("RGB")), dtype=np.float32) / 255.0)[None]
        interp.set_tensor(inp, x)
        interp.invoke()
        got = interp.get_tensor(out)[0]
        ref = reference(input=tf.constant(x))["prediction"].numpy()[0]
        return got, float(np.abs(got - ref).max())

    worst = 0.0
    signals = []
    for name in ["astronaut", "coffee", "chelsea", "rocket", "cat", "horse", "camera", "page",
                 "immunohistochemistry", "grass", "gravel", "logo", "retina", "moon"]:
        photo = Image.fromarray(getattr(skimage.data, name)())
        for tag, im in (("photo", photo), ("screen", phone_screen(photo.convert("RGB")))):
            s, diff = classify(im)
            worst = max(worst, diff)
            signal = s[4] + s[3] + s[1]
            signals.append(signal)
            print(f"{name:22s} {tag:6s} signal={signal:.3f}  " +
                  " ".join(f"{l}={v:.3f}" for l, v in zip(LABELS, s)))
    print(f"max |tflite - SavedModel| = {worst:.6f}")
    print(f"ordinary images: median signal {np.median(signals):.3f}, max {max(signals):.3f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
