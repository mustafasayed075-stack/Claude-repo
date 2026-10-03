"""Per-class false-positive measurement for the additive per-class detection layer
(README "Screen scanning → per-class layer").

The app today sums the classes (region: sexy+porn+hentai >= 0.7; whole screen:
sexy+porn(+hentai>=0.95) >= 0.15). This measures what INDEPENDENT per-class
thresholds would cost in false positives on benign content, so values are chosen
from data, not guessed. It only measures benign FALSE POSITIVES (the real risk of
lowering a threshold); no explicit images are used.

Two benign sources, each in the two contexts the app runs:
  - COCO val people photos (everyday photos of clothed people) — the 'sexy' FP risk;
  - Rico app screenshots (real UI) — the 'hentai'/UI-leak FP risk;
each evaluated as a whole chat screen (whole-screen path) and as a region crop
(region path, with the app's blank-crop skip).

Usage:  pip install ai-edge-litert pillow numpy
        python tools/per_class_eval.py <coco_people_dir> [rico_work_dir] [rico_max_bytes]
"""
import collections
import glob
import os
import runpy
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(__file__)
bench = runpy.run_path(os.path.join(HERE, "region_scan_benchmark.py"))
probs = bench["probs"]                 # -> [safe, hentai, porn, sexy, drawing]
with_photo = bench["with_photo"]
screen_probs_img = bench["probs"]
region_content_ok = bench["region_content_ok"]

# Class indices in the model output.
HENTAI, PORN, SEXY = 1, 2, 3

# Candidate per-class thresholds to sweep.
PORN_T = [0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70]
SEXY_T = [0.30, 0.40, 0.50, 0.60, 0.70]
HENTAI_T = [0.50, 0.60, 0.70, 0.80, 0.90]
FLOOR_T = [0.20, 0.25, 0.30]


def region_probs(crop):
    """Per-class probs for a region crop, or None if the app would skip it as blank."""
    return probs(crop) if region_content_ok(crop) else None


def summarize(name, vectors, is_region):
    """vectors: list of 5-class prob arrays (None = skipped blank region)."""
    kept = [p for p in vectors if p is not None]
    skipped = sum(1 for p in vectors if p is None)
    n = len(vectors)
    print(f"\n[{name}]  {n} items" + (f"  ({skipped} blank-skipped by region path)" if is_region else ""))
    if not kept:
        print("  (nothing to score)")
        return
    m = len(kept)
    def rate(idx, t):
        c = sum(1 for p in kept if p[idx] >= t)
        return f"{c:4d}/{m} ({100*c/m:4.1f}%)"
    print("  porn :  " + "   ".join(f">={t}:{rate(PORN, t)}" for t in PORN_T))
    print("  sexy :  " + "   ".join(f">={t}:{rate(SEXY, t)}" for t in SEXY_T))
    print("  hentai: " + "   ".join(f">={t}:{rate(HENTAI, t)}" for t in HENTAI_T))
    floor = "  floor max(sexy,porn,hentai): " + "   ".join(
        f">={t}:" + f"{sum(1 for p in kept if max(p[SEXY],p[PORN],p[HENTAI])>=t):4d}/{m}" for t in FLOOR_T)
    print(floor)
    for idx, nm in ((PORN, "porn"), (SEXY, "sexy"), (HENTAI, "hentai")):
        vals = sorted(p[idx] for p in kept)
        p50, p95, p99, mx = vals[m//2], vals[min(m-1,(95*m)//100)], vals[min(m-1,(99*m)//100)], vals[-1]
        print(f"    {nm:6s} percentiles  median {p50:.3f}  p95 {p95:.3f}  p99 {p99:.3f}  max {mx:.3f}")


def coco(coco_dir):
    files = sorted(glob.glob(os.path.join(coco_dir, "*.jpg")))
    photos = [Image.open(f).convert("RGB") for f in files]
    alone, whole, region = [], [], []
    for ph in photos:
        alone.append(probs(ph))
        s, box = with_photo(ph, 450)
        whole.append(screen_probs_img(s))      # whole-screen path sees the whole composite
        region.append(region_probs(s.crop(box)))
    print(f"\n==== COCO people photos: {len(photos)} ====")
    summarize("COCO photo alone (region-sized)", alone, is_region=False)
    summarize("COCO as region crop (450px in chat)", region, is_region=True)
    summarize("COCO whole chat screen", whole, is_region=False)


def rico(path, max_bytes):
    rico_mod = runpy.run_path(os.path.join(HERE, "region_rico_eval.py"))
    kind_of, select, area, load_samples, BASE = (
        rico_mod["kind_of"], rico_mod["select"], rico_mod["area"], rico_mod["load_samples"], rico_mod["BASE"])
    import urllib.request
    os.makedirs(os.path.join(path, "shots"), exist_ok=True)
    samples = load_samples(path, max_bytes)
    min_side = int(64 * 2.625)
    whole, region = [], []
    labels = collections.Counter()
    for fp, w, h, dets in samples:
        cands = []
        for d in dets:
            k = kind_of(d.get("type"), d.get("resource_id"), None)
            if not k:
                continue
            x, y, bw, bh = d["bounding_box"]
            box = (max(0, int(x*w)), max(0, int(y*h)), min(w, int((x+bw)*w)), min(h, int((y+bh)*h)))
            if area(box):
                cands.append({"box": box, "w": box[2]-box[0], "h": box[3]-box[1], "kind": k, "label": d["label"]})
        picked = select(cands, w, h, min_side)
        if not picked:
            continue
        shot = os.path.join(path, "shots", fp.replace("/", "_"))
        if not os.path.exists(shot):
            try:
                urllib.request.urlretrieve(BASE + fp, shot)
            except Exception:
                continue
        try:
            im = Image.open(shot).convert("RGB")
        except Exception:
            continue
        whole.append(screen_probs_img(im))
        for c in picked:
            labels[c["label"]] += 1
            region.append(region_probs(im.crop(c["box"])))
    print(f"\n==== Rico app screens: {len(whole)} screens, {len(region)} regions ====")
    print(f"Rico region labels: {labels.most_common(12)}")
    summarize("Rico whole screen (UI)", whole, is_region=False)
    summarize("Rico region crop (UI element)", region, is_region=True)


def main():
    coco(sys.argv[1])
    if len(sys.argv) > 2:
        rico(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 45_000_000)


if __name__ == "__main__":
    main()
