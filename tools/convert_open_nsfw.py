"""Rebuilds app/src/main/assets/models/open_nsfw.tflite from the OpenNSFW2 weights.

Model: Yahoo open_nsfw (ResNet-50 "thin", BSD-2-Clause), as ported to Keras by
bhky/opennsfw2 (MIT). Input: 1x224x224x3 float32, BGR, VGG-mean subtracted.
Output: 1x2 softmax [sfw, nsfw]. See README "Stage 3" for provenance.

The TFLite file uses float16 weights (~12 MB). It was checked against the Keras
reference model on sample images: max |nsfw diff| = 0.0013. (Dynamic-range int8
halves the size but drifted by up to 0.085, too much near the 0.8 threshold.)

Usage (from a scratch virtualenv):
    pip install tensorflow-cpu opennsfw2
    python tools/convert_open_nsfw.py app/src/main/assets/models/open_nsfw.tflite
"""
import sys

import numpy as np
import opennsfw2 as n2
import tensorflow as tf
from PIL import Image
import skimage.data


def main(out_path: str) -> None:
    model = n2.make_open_nsfw_model()  # downloads the pre-trained weights on first use

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite = converter.convert()

    # Sanity check: the converted model must agree with the Keras reference.
    samples = [skimage.data.astronaut(), skimage.data.coffee(), skimage.data.chelsea()]
    x = np.stack([n2.preprocess_image(Image.fromarray(s)) for s in samples]).astype(np.float32)
    reference = model.predict(x, verbose=0)[:, 1]
    interp = tf.lite.Interpreter(model_content=tflite)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    for sample, ref in zip(x, reference):
        interp.set_tensor(inp["index"], sample[None])
        interp.invoke()
        got = interp.get_tensor(out["index"])[0, 1]
        assert abs(got - ref) < 0.01, f"TFLite drifted from reference: {got} vs {ref}"

    with open(out_path, "wb") as f:
        f.write(tflite)
    print(f"wrote {out_path} ({len(tflite) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "open_nsfw.tflite")
