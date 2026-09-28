"""Off-device validation for Stage 3 region scanning (README "Region scanning").

Runs the bundled TFLite model (viddexa-nano) with the app's preprocessing
(nearest-neighbour resize to 224x224, RGB / 255, 2 threads) and the app's two
signals — regions: sexy + porn + hentai (REGION_THRESHOLD); whole screen: sexy +
porn, hentai only when >= 0.95 (NSFW_THRESHOLD) — and prints:
  1. false positives: each photo alone vs. as a chat image on a 1080x2400 chat-like
     screen, classified by the whole-screen pass and as a region crop;
     with an optional second directory of revealing (non-explicit) photos, the same
     for recall;
  2. cost per capture for typical scenarios (region cache included).
No explicit images are needed or used: pass a directory of everyday photos (the
README numbers used 400 random COCO-2017 val photos containing people).

Usage:  pip install ai-edge-litert pillow numpy
        python tools/region_scan_benchmark.py /path/to/photos [/path/to/revealing_photos]
"""
import glob
import os
import statistics
import sys
import time

import numpy as np
from PIL import Image, ImageDraw
from ai_edge_litert.interpreter import Interpreter

MODEL = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models",
                     "viddexa_nsfw_detection_2_nano_224.tflite")
SCREEN_THRESHOLD = 0.15   # ScanConfig.NSFW_THRESHOLD
REGION_THRESHOLD = 0.7    # ScanConfig.REGION_THRESHOLD
HENTAI_GATE = 0.95        # ScanConfig.SCREEN_HENTAI_GATE
T = 224
it = Interpreter(model_path=MODEL, num_threads=2)
it.allocate_tensors()
INP, OUT = it.get_input_details()[0]["index"], it.get_output_details()[0]["index"]


def downscale(img):  # mirrors NsfwPreprocessor.downscaleSteps (filtered halving; not the model input)
    w, h = img.size
    while w > 2 * T or h > 2 * T:
        if w > 2 * T:
            w //= 2
        if h > 2 * T:
            h //= 2
        img = img.resize((w, h), Image.BILINEAR)
    return img.resize((T, T), Image.BILINEAR) if (w, h) != (T, T) else img


def probs(img):
    """Model input as in the app: one nearest-neighbour resize (NsfwPreprocessor.RESIZE_FILTER = false)."""
    x = np.asarray(img.convert("RGB").resize((T, T), Image.NEAREST), dtype=np.float32)[None] / 255.0
    it.set_tensor(INP, x)
    it.invoke()
    return it.get_tensor(OUT)[0]  # safe, hentai, porn, sexy, drawing


def signal(img):
    """NsfwScores.signal (regions): sexy + porn + hentai."""
    p = probs(img)
    return float(p[1] + p[2] + p[3])


def screen_signal(img):
    """NsfwScores.screenSignal (whole screen): sexy + porn, hentai only when >= HENTAI_GATE."""
    p = probs(img)
    return float(p[2] + p[3] + (p[1] if p[1] >= HENTAI_GATE else 0.0))


def chat_screen():
    w, h = 1080, 2400
    s = Image.new("RGB", (w, h), (236, 229, 221))
    d = ImageDraw.Draw(s)
    d.rectangle([0, 0, w, 300], fill=(7, 94, 84))
    d.rectangle([0, h - 220, w, h], fill=(245, 245, 245))
    for y in range(340, h - 260, 160):
        d.rounded_rectangle([40, y, 700, y + 110], 20, fill=(255, 255, 255))
    return s


def with_photo(photo, box_w, y=700):
    s = chat_screen()
    r = photo.size[1] / photo.size[0]
    bw, bh = box_w, int(box_w * r)
    if bh > 1200:
        bh, bw = 1200, int(1200 / r)
    x = 1080 - bw - 40
    s.paste(photo.resize((bw, bh), Image.BILINEAR), (x, y))
    return s, (x, y, x + bw, y + bh)


def region_content_ok(crop, grid=48):
    """Mirror of RegionContent.assess (ImageRegions.kt): False for blank, flat,
    near-black or mostly-empty crops, which the app skips without classifying."""
    small = crop
    w, h = crop.size
    while w > 2 * grid or h > 2 * grid:
        if w > 2 * grid:
            w //= 2
        if h > 2 * grid:
            h //= 2
        small = small.resize((w, h), Image.BILINEAR)
    small = small.resize((grid, grid), Image.BILINEAR)
    a = np.asarray(small.convert("RGB"), dtype=np.int32)
    lum = (a[..., 0] * 299 + a[..., 1] * 587 + a[..., 2] * 114) // 1000
    if lum.mean() < 16 and np.sort(lum.flatten())[(lum.size * 95) // 100] < 40:
        return False
    def flat(v):
        return v.size == 0 or v.max() - v.min() <= 10
    t, b, l, r = 0, grid, 0, grid
    while t < b and flat(lum[t, l:r]):
        t += 1
    while b > t and flat(lum[b - 1, l:r]):
        b -= 1
    while l < r and flat(lum[t:b, l]):
        l += 1
    while r > l and flat(lum[t:b, r - 1]):
        r -= 1
    if t >= b or l >= r:
        return False
    c = lum[1:-1, 1:-1]
    f = ((abs(c - lum[:-2, 1:-1]) <= 2) & (abs(c - lum[2:, 1:-1]) <= 2) &
         (abs(c - lum[1:-1, :-2]) <= 2) & (abs(c - lum[1:-1, 2:]) <= 2))
    if f.sum() >= 0.9 * (grid - 2) ** 2:
        return False
    return (b - t) * (r - l) >= 0.15 * grid * grid


def region_signal(crop):
    """Region path as in the app: blank crops are skipped (signal 0)."""
    return signal(crop) if region_content_ok(crop) else 0.0


def dhash(img):
    g = np.asarray(img.resize((9, 8), Image.BILINEAR).convert("L"), dtype=np.int32)
    return int("".join("1" if b else "0" for b in (g[:, :-1] > g[:, 1:]).flatten()), 2)


def evaluate(photos, label):
    res = {k: [] for k in ("photo alone (region)", "whole 700px", "region 700px", "whole 450px", "region 450px")}
    for ph in photos:
        res["photo alone (region)"].append(signal(ph))
        for bw in (700, 450):
            s, box = with_photo(ph, bw)
            res[f"whole {bw}px"].append(screen_signal(s))
            res[f"region {bw}px"].append(region_signal(s.crop(box)))
    n = len(photos)
    print(f"\n{label}: {n} photos (whole screen at {SCREEN_THRESHOLD}, regions at {REGION_THRESHOLD})")
    for k, v in res.items():
        th = SCREEN_THRESHOLD if k.startswith("whole") else REGION_THRESHOLD
        print(f"  {k:21s} >= {th}: {sum(x >= th for x in v):4d}/{n}   median {statistics.median(v):.3f}")
    for bw in (700, 450):
        dr = statistics.median(abs(a - b) for a, b in zip(res["photo alone (region)"], res[f"region {bw}px"]))
        print(f"  |region signal - photo alone| median at {bw}px: {dr:.3f}")
    for t in (0.3, 0.5, 0.7, 0.9):
        print(f"  region 450px >= {t}: {sum(x >= t for x in res['region 450px'])}/{n}")
    for t in (0.1, 0.15, 0.2, 0.3):
        print(f"  whole 450px  >= {t}: {sum(x >= t for x in res['whole 450px'])}/{n}")


BOXES = [(560, 500, 1040, 980), (340, 1050, 1040, 1575), (560, 1650, 1040, 2130)]


def cost(photos, n_regions, changing, captures=40):
    cache, whole, reg, inferences, k = {}, [], [], 0, 0
    for _ in range(captures):
        s = chat_screen()
        for i in range(n_regions):
            if i < changing:
                k += 1
                ph = photos[k % len(photos)]
            else:
                ph = photos[i]
            b = BOXES[i]
            s.paste(ph.resize((b[2] - b[0], b[3] - b[1])), (b[0], b[1]))
        t0 = time.perf_counter()
        screen_signal(s)
        t1 = time.perf_counter()
        for i in range(n_regions):
            crop = s.crop(BOXES[i])
            key = (dhash(crop), crop.size)
            if key not in cache:  # cache checked first; blank verdicts are cached too (as in the app)
                cache[key] = region_signal(crop)
                inferences += 1
        t2 = time.perf_counter()
        whole.append((t1 - t0) * 1000)
        reg.append((t2 - t1) * 1000)
    return statistics.mean(whole), statistics.mean(reg), inferences


def main():
    photos = [Image.open(f).convert("RGB") for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.jpg")))]
    evaluate(photos, "Ordinary photos (false positives)")
    if len(sys.argv) > 2:
        revealing = [Image.open(f).convert("RGB") for f in sorted(glob.glob(os.path.join(sys.argv[2], "*.jpg")))]
        evaluate(revealing, "Revealing, non-explicit photos (recall)")
    print("\nscenario | whole ms/capture | region ms/capture | region inferences per 40 captures | overhead")
    for name, n, ch in [("text chat, no media", 0, 0), ("2 stickers + photo, unchanged", 3, 0),
                        ("scrolling chat, 1 new image per capture", 3, 1),
                        ("small video player (1 changing region)", 1, 1), ("worst case: 3 changing regions", 3, 3)]:
        w, r, inf = cost(photos[:120], n, ch)
        print(f"{name} | {w:.1f} | {r:.1f} | {inf} | +{r / w * 100:.0f}%")


if __name__ == "__main__":
    main()
