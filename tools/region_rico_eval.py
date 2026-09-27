"""Region-detector check on real Android app screens (README "False-positive investigation").

Uses the Rico dataset (real app screenshots with their view hierarchies and element
labels such as Image / Icon / Video; CC BY 4.0, via Hugging Face "Voxel51/rico"),
replicates ImageRegionFinder's rules (ImageRegions.kt) on each hierarchy, crops the
picked elements from the screenshot and scores them with the bundled model through
the app's region path (blank-crop check included; see region_scan_benchmark.py).
Prints the Rico labels of the picked elements and false-positive counts per
threshold. Keep the rules below in sync with ImageRegions.kt.

Usage:  pip install ai-edge-litert pillow numpy
        python tools/region_rico_eval.py /tmp/rico [max_bytes_of_annotations=45000000]
"""
import collections
import json
import os
import re
import runpy
import sys
import urllib.request

from PIL import Image

BASE = "https://huggingface.co/datasets/Voxel51/rico/resolve/main/"
bench = runpy.run_path(os.path.join(os.path.dirname(__file__), "region_scan_benchmark.py"))

# ---- mirror of ImageRegionFinder (ImageRegions.kt) ----
VIDEO = ["VideoView", "SurfaceView", "TextureView", "PlayerView"]
HINTS = {"image", "images", "img", "photo", "photos", "picture", "pictures", "pic", "sticker", "stickers",
         "thumbnail", "thumbnails", "thumb", "media", "video", "videos", "gif", "gifs", "player", "preview", "poster",
         "صورة", "صوره", "ملصق", "فيديو"}
GRAPHIC = {"logo", "logos", "icon", "icons", "ic", "splash", "badge", "badges", "illustration", "illustrations",
           "placeholder", "emoji", "emoticon", "shutter", "watermark", "divider", "shadow", "gradient", "overlay",
           "scrim", "btn", "arrow", "chevron", "mascot", "clipart", "vector", "lottie", "animation"}
CONTAINERS = ["WebView", "RecyclerView", "ListView", "ScrollView", "ViewPager", "Layout"]
NON_MEDIA = ["TextView", "Button", "EditText", "CheckBox", "Switch", "RadioButton", "Spinner", "ProgressBar",
             "SeekBar", "RatingBar", "Toolbar"]
WORD = re.compile(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\d+|[^\W\d_a-zA-Z]+")


def words(text):
    return {w.lower() for w in WORD.findall(text or "")}


def kind_of(cls, view_id, desc):
    cls = cls or ""
    simple = cls.split(".")[-1]
    ws = words((view_id or "").split(":id/")[-1]) | words(desc)
    if ws & GRAPHIC:
        return None
    if simple.endswith("ImageView") or cls == "android.widget.Image" or simple == "ImageButton":
        return "image"
    if any(v in simple for v in VIDEO):
        return "video"
    if any(c in simple for c in CONTAINERS) or any(simple.endswith(n) for n in NON_MEDIA):
        return None
    return "media" if ws & HINTS else None


def area(b):
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


def iou(a, b):
    i = area((max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])))
    return 0 if i == 0 else i / (area(a) + area(b) - i)


def duplicate(a, b):
    if iou(a, b) >= 0.8:
        return True
    o, n = (a, b) if area(a) >= area(b) else (b, a)
    return n[0] >= o[0] and n[1] >= o[1] and n[2] <= o[2] and n[3] <= o[3] and area(n) >= 0.8 * area(o)


def select(cands, w, h, min_side, frac=0.6, aspect=3.0, max_regions=3):
    order = {"image": 0, "video": 1, "media": 2}
    q = [c for c in cands if min(c["w"], c["h"]) >= min_side and area(c["box"]) / (w * h) <= frac
         and max(c["w"], c["h"]) / min(c["w"], c["h"]) <= aspect]
    kept = []
    for c in sorted(q, key=lambda c: (order[c["kind"]], area(c["box"]))):
        if not any(duplicate(k["box"], c["box"]) for k in kept):
            kept.append(c)
    return sorted(kept, key=lambda c: -area(c["box"]))[:max_regions]


def load_samples(path, max_bytes):
    part = os.path.join(path, "samples_part.json")
    if not os.path.exists(part):
        req = urllib.request.Request(BASE + "samples.json", headers={"Range": f"bytes=0-{max_bytes}"})
        with urllib.request.urlopen(req) as r, open(part, "wb") as f:
            f.write(r.read())
    s = open(part, encoding="utf-8", errors="ignore").read()
    i, dec, out = s.index("[") + 1, json.JSONDecoder(), []
    while True:
        while i < len(s) and s[i] in " ,\n":
            i += 1
        try:
            obj, i = dec.raw_decode(s, i)
        except ValueError:
            return out
        dets = (obj.get("detections") or {}).get("detections") or []
        md = obj.get("metadata") or {}
        if dets and md.get("width"):
            out.append((obj["filepath"], md["width"], md["height"], dets))


def main():
    path = sys.argv[1]
    os.makedirs(os.path.join(path, "shots"), exist_ok=True)
    samples = load_samples(path, int(sys.argv[2]) if len(sys.argv) > 2 else 45_000_000)
    min_side = int(64 * 2.625)  # 64 dp on Rico's 1080-wide screenshots (1440 px @ 3.5 originally)
    labels, sig, screens = collections.Counter(), [], []
    for fp, w, h, dets in samples:
        cands = []
        for d in dets:
            k = kind_of(d.get("type"), d.get("resource_id"), None)
            if not k:
                continue
            x, y, bw, bh = d["bounding_box"]
            box = (max(0, int(x * w)), max(0, int(y * h)), min(w, int((x + bw) * w)), min(h, int((y + bh) * h)))
            if area(box):
                cands.append({"box": box, "w": box[2] - box[0], "h": box[3] - box[1], "kind": k, "label": d["label"]})
        picked = select(cands, w, h, min_side)
        if not picked:
            continue
        shot = os.path.join(path, "shots", fp.replace("/", "_"))
        if not os.path.exists(shot):
            urllib.request.urlretrieve(BASE + fp, shot)
        im = Image.open(shot).convert("RGB")
        best = 0.0
        for c in picked:
            labels[c["label"]] += 1
            v = bench["region_signal"](im.crop(c["box"]))
            sig.append(v)
            best = max(best, v)
        screens.append(best)
    print(f"{len(samples)} screens, {len(screens)} with regions, {len(sig)} regions; Rico labels: {labels.most_common()}")
    for t in (0.3, 0.5, 0.7, 0.9):
        print(f"  >= {t}: {sum(v >= t for v in sig)} regions on {sum(b >= t for b in screens)} screens")


if __name__ == "__main__":
    main()
