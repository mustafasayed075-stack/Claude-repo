package com.personal.guardian.scan

import java.util.Locale

/**
 * Stage 3 region scanning: finds image-bearing UI elements (image views, video
 * surfaces, sticker/media views) in the accessibility node tree, so each can be
 * cropped out of the screenshot and classified at its own resolution — a sticker or
 * a small player otherwise shrinks to a few dozen pixels in the whole-screen pass
 * (README "Stage 3 — region scanning").
 *
 * Pure Kotlin over [RegionNode], so detection, filtering and cropping are
 * unit-tested without Android.
 */

/** Axis-aligned rectangle in pixels, `right`/`bottom` exclusive (like `android.graphics.Rect`). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height
    val isEmpty: Boolean get() = width == 0 || height == 0

    fun intersect(o: Box): Box =
        Box(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))
            .let { if (it.isEmpty) EMPTY else it }

    fun contains(o: Box): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    /** Intersection over union, 0..1. */
    fun iou(o: Box): Float {
        val inter = intersect(o).area
        if (inter == 0L) return 0f
        return inter.toFloat() / (area + o.area - inter)
    }

    override fun toString() = "${width}x$height@$left,$top"

    companion object {
        val EMPTY = Box(0, 0, 0, 0)
    }
}

/** Read-only view of an accessibility node, as far as region detection needs it. */
interface RegionNode {
    val className: CharSequence?
    val viewId: String?
    val contentDescription: CharSequence?
    val isVisibleToUser: Boolean
    /** Bounds in screen pixels (`AccessibilityNodeInfo.getBoundsInScreen`). */
    val boundsInScreen: Box
    val childCount: Int
    fun child(index: Int): RegionNode?
    /** Releases the underlying node (children are released by the walker after use). */
    fun release()
}

enum class RegionKind(val label: String) {
    /** An image view (ImageView and subclasses, web `<img>` = `android.widget.Image`). */
    IMAGE("image"),
    /** A video surface or player view. */
    VIDEO("video"),
    /** Any other view whose id or description names media (sticker, photo, gif…), e.g. Telegram's drawn cells. */
    MEDIA_HINT("media");
}

/** One image-bearing element to classify: where it is and why it was picked. */
data class ImageRegion(
    val box: Box,
    val kind: RegionKind,
    val className: String,
    /** Resource id without the package (`profile_picture`), if any — for diagnosing reports. */
    val viewId: String? = null
) {
    /**
     * Short label for logs and detection metadata, e.g.
     * `image 540x540@480,900 (ImageView #profile_picture)`.
     */
    val label: String get() = "${kind.label} $box (${className.substringAfterLast('.')}${viewId?.let { " #$it" } ?: ""})"
}

object ImageRegionFinder {

    /** Filters applied to candidate regions; defaults from [ScanConfig]. */
    data class Limits(
        /** Shorter side at least this many pixels (avatars and icons are smaller). */
        val minSidePx: Int,
        /** Regions covering more than this fraction of the screen are left to the whole-screen pass. */
        val maxScreenFraction: Float = ScanConfig.REGION_MAX_SCREEN_FRACTION,
        /** Long thin strips (banners, dividers, progress bars) are skipped. */
        val maxAspect: Float = ScanConfig.REGION_MAX_ASPECT,
        /** At most this many regions per capture: the largest qualifying ones. */
        val maxRegions: Int = ScanConfig.REGION_MAX_PER_CAPTURE,
        /** Node-walk bound, so a huge page can't stall the scanner. */
        val maxNodes: Int = ScanConfig.REGION_MAX_NODES
    )

    private val VIDEO_CLASSES = listOf("VideoView", "SurfaceView", "TextureView", "PlayerView")

    /** Whole words in an id or description that mark a media view (matched as words: "gift" ≠ "gif"). */
    private val MEDIA_HINTS = setOf(
        "image", "images", "img", "photo", "photos", "picture", "pictures", "pic", "sticker", "stickers",
        "thumbnail", "thumbnails", "thumb", "media", "video", "videos", "gif", "gifs", "player", "preview", "poster",
        "صورة", "صوره", "ملصق", "فيديو"
    )

    /**
     * Whole words that mark UI graphics — never photos — on *any* element, image views
     * included: app/brand logos, icons, splash art, badges, illustrations, emoji,
     * placeholders, decorative overlays, controls. On RICO app screens these made up
     * most wrongly picked "image" regions (`icon`, `logo`, `item_icon`, `img_splash_logo`,
     * `vendorLogoImageView`, `emoji_image`, `record_preview_shutter`…).
     */
    private val GRAPHIC_WORDS = setOf(
        "logo", "logos", "icon", "icons", "ic", "splash", "badge", "badges", "illustration", "illustrations",
        "placeholder", "emoji", "emoticon", "shutter", "watermark", "divider", "shadow", "gradient", "overlay",
        "scrim", "btn", "arrow", "chevron", "mascot", "clipart", "vector", "lottie", "animation"
    )

    /** Containers that are never an image themselves (their children are still walked). */
    private val CONTAINER_CLASSES = listOf("WebView", "RecyclerView", "ListView", "ScrollView", "ViewPager", "Layout")

    /** Widgets that aren't media even when their id or text mentions it ("Add photo" buttons, captions). */
    private val NON_MEDIA_CLASSES = listOf(
        "TextView", "Button", "EditText", "CheckBox", "Switch", "RadioButton", "Spinner", "ProgressBar",
        "SeekBar", "RatingBar", "Toolbar"
    )

    private val WORD = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\\d+|[\\p{L}&&[^\\p{IsLatin}]]+")

    /** Lower-cased words of an id or description: `imgSplash_logo2` → img, splash, logo, 2. */
    internal fun words(text: String?): Set<String> =
        if (text.isNullOrEmpty()) emptySet()
        else WORD.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toSet()

    /**
     * What kind of image-bearing element a node is, or null. Class names come from
     * `AccessibilityNodeInfo.getClassName` — the platform base class for most custom
     * views (AppCompatImageView, Fresco's DraweeView → `android.widget.ImageView`).
     * An id or description naming a UI graphic (logo, icon, splash…) excludes the node
     * whatever its class.
     */
    fun kindOf(className: CharSequence?, viewId: String?, contentDescription: CharSequence?): RegionKind? {
        val cls = className?.toString().orEmpty()
        val simple = cls.substringAfterLast('.')
        val words = words(viewId?.substringAfter(":id/")) + words(contentDescription?.toString())
        if (words.any { it in GRAPHIC_WORDS }) return null
        if (simple.endsWith("ImageView") || cls == "android.widget.Image" || simple == "ImageButton") return RegionKind.IMAGE
        if (VIDEO_CLASSES.any { simple.contains(it) }) return RegionKind.VIDEO
        if (CONTAINER_CLASSES.any { simple.contains(it) }) return null
        if (NON_MEDIA_CLASSES.any { simple.endsWith(it) }) return null
        if (words.any { it in MEDIA_HINTS }) return RegionKind.MEDIA_HINT
        return null
    }

    /**
     * Walks the tree under [root] (depth-first, visible nodes only, at most
     * [Limits.maxNodes]), then [select]s the regions to classify. Releases every
     * child node it obtains; the caller releases [root].
     */
    fun collect(root: RegionNode, screen: Box, limits: Limits): List<ImageRegion> {
        val candidates = ArrayList<ImageRegion>()
        var visited = 0
        fun walk(node: RegionNode) {
            if (visited >= limits.maxNodes || !node.isVisibleToUser) return
            visited++
            kindOf(node.className, node.viewId, node.contentDescription)?.let { kind ->
                val box = node.boundsInScreen.intersect(screen)
                if (!box.isEmpty) {
                    candidates += ImageRegion(box, kind, node.className?.toString().orEmpty(), node.viewId?.substringAfter(":id/"))
                }
            }
            for (i in 0 until node.childCount) {
                if (visited >= limits.maxNodes) break
                val child = node.child(i) ?: continue
                try {
                    walk(child)
                } finally {
                    child.release()
                }
            }
        }
        walk(root)
        return select(candidates, screen, limits)
    }

    /**
     * Keeps candidates that are big enough, not (nearly) the whole screen and not
     * strip-shaped; merges duplicates (the same element reported twice, or a media
     * container around its own image — the tighter, more specific box wins); returns
     * the [Limits.maxRegions] largest, biggest first.
     */
    fun select(candidates: List<ImageRegion>, screen: Box, limits: Limits): List<ImageRegion> {
        val screenArea = screen.area.coerceAtLeast(1)
        val qualifying = candidates.filter { r ->
            val b = r.box
            val short = minOf(b.width, b.height)
            val long = maxOf(b.width, b.height)
            short >= limits.minSidePx &&
                b.area.toFloat() / screenArea <= limits.maxScreenFraction &&
                long.toFloat() / short <= limits.maxAspect
        }
        val kept = ArrayList<ImageRegion>()
        // Specific kinds first, then smaller boxes: the first of two duplicates is kept.
        for (r in qualifying.sortedWith(compareBy<ImageRegion> { it.kind.ordinal }.thenBy { it.box.area })) {
            if (kept.none { k -> isDuplicate(k.box, r.box) }) kept += r
        }
        return kept.sortedByDescending { it.box.area }.take(limits.maxRegions)
    }

    /** Same element: boxes overlap almost entirely, or one holds the other with ≥ 80% of its area. */
    internal fun isDuplicate(a: Box, b: Box): Boolean {
        if (a.iou(b) >= 0.8f) return true
        val (outer, inner) = if (a.area >= b.area) a to b else b to a
        return outer.contains(inner) && inner.area >= 0.8 * outer.area
    }
}

object RegionCrop {

    /**
     * The pixel rectangle of [region] (screen coordinates, [screenWidth]×[screenHeight])
     * inside a screenshot of [bitmapWidth]×[bitmapHeight], clipped to the bitmap; null
     * if less than [minSidePx] survives. The crop is the element's exact bounds —
     * squashed to the model input like the model's training images were, rather than
     * padded with surrounding UI.
     */
    fun cropRect(
        region: Box, screenWidth: Int, screenHeight: Int, bitmapWidth: Int, bitmapHeight: Int, minSidePx: Int = 1
    ): Box? {
        if (screenWidth <= 0 || screenHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val sx = bitmapWidth.toDouble() / screenWidth
        val sy = bitmapHeight.toDouble() / screenHeight
        val mapped = Box(
            Math.floor(region.left * sx).toInt(), Math.floor(region.top * sy).toInt(),
            Math.ceil(region.right * sx).toInt(), Math.ceil(region.bottom * sy).toInt()
        ).intersect(Box(0, 0, bitmapWidth, bitmapHeight))
        return mapped.takeIf { !it.isEmpty && minOf(it.width, it.height) >= minSidePx }
    }
}

/**
 * Remembers recent region scores by content, so an unchanged sticker or photo that
 * stays on screen across captures (1.5 s apart in fast mode) is classified once, not
 * every capture. Key: the crop's 64-bit dHash plus its size; only an exact match
 * hits (a near-match could be a different image). LRU, [capacity] entries.
 * Not thread-safe (scanner worker thread).
 */
class RegionScoreCache(private val capacity: Int = ScanConfig.REGION_CACHE_SIZE) {
    private data class Key(val hash: Long, val width: Int, val height: Int)

    /** A cached outcome: the scores, or null for a crop found blank ([RegionContent]). */
    class Entry(val scores: NsfwScores?)

    private val map = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) = size > capacity
    }

    val size: Int get() = map.size

    fun lookup(hash: Long, width: Int, height: Int): Entry? = map[Key(hash, width, height)]

    fun get(hash: Long, width: Int, height: Int): NsfwScores? = lookup(hash, width, height)?.scores

    fun put(hash: Long, width: Int, height: Int, scores: NsfwScores) {
        map[Key(hash, width, height)] = Entry(scores)
    }

    /** Remembers that this crop was blank, so an unchanged placeholder isn't re-checked every capture. */
    fun putBlank(hash: Long, width: Int, height: Int) {
        map[Key(hash, width, height)] = Entry(null)
    }

    fun clear() = map.clear()
}

/**
 * Running cost figures for the capture pipeline, logged periodically so the region
 * pass's CPU cost can be checked on a real device (README "Stage 3 — region scanning").
 * Pure Kotlin; not thread-safe (scanner worker thread).
 */
class ScanCostStats(private val reportEvery: Int = ScanConfig.COST_LOG_EVERY_CAPTURES) {
    var captures = 0; private set
    private var wholeMs = 0L
    private var regionMs = 0L
    private var regionsFound = 0
    private var classified = 0
    private var cacheHits = 0
    private var budgetSkips = 0
    private var blankSkips = 0

    /** Records one capture; returns a summary line every [reportEvery] captures (then resets), else null. */
    fun record(
        wholeMs: Long, regionMs: Long, regionsFound: Int, classified: Int, cacheHits: Int, budgetSkips: Int,
        blankSkips: Int = 0
    ): String? {
        captures++
        this.wholeMs += wholeMs
        this.regionMs += regionMs
        this.regionsFound += regionsFound
        this.classified += classified
        this.cacheHits += cacheHits
        this.budgetSkips += budgetSkips
        this.blankSkips += blankSkips
        if (captures < reportEvery) return null
        val n = captures.toDouble()
        val line = String.format(
            Locale.US,
            "Scan cost (last %d captures): whole-screen %.1f ms avg, regions %.1f ms avg " +
                "(%.2f regions/capture: %d classified, %d cached, %d blank/flat skipped, %d skipped by time budget)",
            captures, this.wholeMs / n, this.regionMs / n, this.regionsFound / n,
            this.classified, this.cacheHits, this.blankSkips, this.budgetSkips
        )
        reset()
        return line
    }

    private fun reset() {
        captures = 0; wholeMs = 0; regionMs = 0; regionsFound = 0; classified = 0; cacheHits = 0; budgetSkips = 0
        blankSkips = 0
    }
}

/** A classified region of one capture. */
data class RegionScore(
    val region: ImageRegion,
    /** Pixel rectangle in the screenshot that was classified. */
    val crop: Box,
    val scores: NsfwScores,
    /** dHash of the crop (score-cache key and same-content cooldown fingerprint). */
    val fingerprint: Long,
    val cached: Boolean
)

/**
 * Combines one capture's whole-screen score with its region scores into a single
 * frame verdict for [DetectionConfirmer]: the frame is positive if the whole screen
 * reaches [wholeThreshold] **or** any region reaches [regionThreshold]. The reported
 * score/classes are those of the strongest positive (a region wins over the whole
 * screen only when it scores higher); with no positive, the highest signal is
 * reported for the log. Pure Kotlin.
 */
object FrameVerdict {

    data class Result(
        val positive: Boolean,
        val score: Float,
        val scores: NsfwScores,
        /** The region that decided the verdict, or null for the whole screen. */
        val region: RegionScore?
    )

    fun combine(
        whole: NsfwScores,
        regions: List<RegionScore>,
        wholeThreshold: Float = ScanConfig.NSFW_THRESHOLD,
        regionThreshold: Float = ScanConfig.REGION_THRESHOLD
    ): Result {
        val wholePositive = whole.signal >= wholeThreshold
        val bestRegion = regions.maxByOrNull { it.scores.signal }
        val regionPositive = bestRegion != null && bestRegion.scores.signal >= regionThreshold
        val useRegion = bestRegion != null &&
            ((regionPositive && (!wholePositive || bestRegion.scores.signal > whole.signal)) ||
                (!wholePositive && !regionPositive && bestRegion.scores.signal > whole.signal))
        return if (useRegion) Result(wholePositive || regionPositive, bestRegion!!.scores.signal, bestRegion.scores, bestRegion)
        else Result(wholePositive || regionPositive, whole.signal, whole, null)
    }
}

/**
 * Scores one capture's regions: crops each out of the frame, serves unchanged
 * content from [cache], classifies the rest, and stops once [budgetMs] of region
 * work has elapsed (the remaining regions are counted as skipped). Generic over the
 * image type [B] (a `Bitmap` on the device, a fake in tests), with the platform
 * operations injected. Not thread-safe (scanner worker thread).
 */
class RegionScorer(
    private val cache: RegionScoreCache,
    private val budgetMs: Long = ScanConfig.REGION_TIME_BUDGET_MS,
    private val clock: () -> Long
) {
    /** Result of one capture's region pass. */
    class Pass(
        val scores: List<RegionScore>, val found: Int, val classified: Int, val cacheHits: Int, val budgetSkips: Int,
        /** Regions not classified because the crop was blank, flat or near-black ([RegionContent]). */
        val blankSkips: Int = 0
    ) {
        companion object {
            val NONE = Pass(emptyList(), 0, 0, 0, 0)
        }
    }

    fun <B> score(
        regions: List<ImageRegion>,
        frameWidth: Int,
        frameHeight: Int,
        minSidePx: Int,
        crop: (Box) -> B,
        fingerprint: (B) -> Long,
        classify: (B) -> NsfwScores,
        release: (B) -> Unit,
        content: (B) -> RegionContent.Verdict = { RegionContent.Verdict.OK }
    ): Pass {
        val scores = ArrayList<RegionScore>(regions.size)
        var classified = 0
        var cacheHits = 0
        var skipped = 0
        var blank = 0
        val start = clock()
        for ((index, region) in regions.withIndex()) {
            if (clock() - start > budgetMs) {
                skipped = regions.size - index
                break
            }
            // Node bounds are screen pixels = the screenshot's pixel space.
            val rect = RegionCrop.cropRect(region.box, frameWidth, frameHeight, frameWidth, frameHeight, minSidePx)
                ?: continue
            val image = crop(rect)
            try {
                // Cache first: an unchanged sticker or placeholder costs only its fingerprint.
                val hash = fingerprint(image)
                val entry = cache.lookup(hash, rect.width, rect.height)
                if (entry != null) {
                    cacheHits++
                    if (entry.scores == null) blank++
                    else scores += RegionScore(region, rect, entry.scores, hash, cached = true)
                    continue
                }
                if (content(image) != RegionContent.Verdict.OK) {
                    cache.putBlank(hash, rect.width, rect.height)
                    blank++
                    continue
                }
                val result = classify(image).also { cache.put(hash, rect.width, rect.height, it) }
                classified++
                scores += RegionScore(region, rect, result, hash, cached = false)
            } finally {
                release(image)
            }
        }
        return Pass(scores, regions.size, classified, cacheHits, skipped, blank)
    }
}

/**
 * Pixel check on a region crop before it reaches the photo classifier: skips crops
 * that carry no image — a blank or placeholder view, a still-loading image, a video
 * surface that screenshots as black, or a view whose bounds are mostly empty
 * margin around a small drawable. On RICO app screens such crops produced some of
 * the highest scores (a mostly white box with a sliver of photo: 1.00 "porn"; a
 * near-black thumbnail: 0.66). Pure Kotlin on a small [GRID]×[GRID] downsample.
 */
object RegionContent {

    const val GRID = 48

    enum class Verdict { OK, FLAT, DARK, MOSTLY_EMPTY }

    /** A row/column counts as empty margin when its luma varies by at most this much. */
    private const val FLAT_LINE_RANGE = 10
    /** A pixel is "flat" when it differs from all 4 neighbours by at most this much luma. */
    private const val FLAT_PIXEL_DIFF = 2
    /** Skip when at least this fraction of pixels is flat (placeholder, solid block). */
    const val MAX_FLAT_FRACTION = 0.9f
    /** Skip when what's left after trimming empty margins covers less than this fraction. */
    const val MIN_CONTENT_FRACTION = 0.15f

    /** [pixels]: ARGB ints, [GRID]×[GRID], row-major (as from `Bitmap.getPixels` on a scaled crop). */
    fun assess(pixels: IntArray): Verdict {
        require(pixels.size == GRID * GRID) { "expected ${GRID}x$GRID pixels" }
        val l = IntArray(pixels.size) { luma(pixels[it]) }
        fun at(x: Int, y: Int) = l[y * GRID + x]

        // Near-black: dark mean and no bright detail (e.g. a video surface captured black).
        val sorted = l.sortedArray()
        if (l.average() < 16 && sorted[(sorted.size * 95) / 100] < 40) return Verdict.DARK

        // Trim flat border lines from each side.
        fun rowFlat(y: Int, x0: Int, x1: Int): Boolean {
            var min = 255; var max = 0
            for (x in x0 until x1) { val v = at(x, y); if (v < min) min = v; if (v > max) max = v }
            return x1 <= x0 || max - min <= FLAT_LINE_RANGE
        }
        fun colFlat(x: Int, y0: Int, y1: Int): Boolean {
            var min = 255; var max = 0
            for (y in y0 until y1) { val v = at(x, y); if (v < min) min = v; if (v > max) max = v }
            return y1 <= y0 || max - min <= FLAT_LINE_RANGE
        }
        var top = 0; var bottom = GRID; var left = 0; var right = GRID
        while (top < bottom && rowFlat(top, left, right)) top++
        while (bottom > top && rowFlat(bottom - 1, left, right)) bottom--
        while (left < right && colFlat(left, top, bottom)) left++
        while (right > left && colFlat(right - 1, top, bottom)) right--
        if (top >= bottom || left >= right) return Verdict.FLAT

        var flat = 0
        for (y in 1 until GRID - 1) for (x in 1 until GRID - 1) {
            val v = at(x, y)
            if (Math.abs(v - at(x - 1, y)) <= FLAT_PIXEL_DIFF && Math.abs(v - at(x + 1, y)) <= FLAT_PIXEL_DIFF &&
                Math.abs(v - at(x, y - 1)) <= FLAT_PIXEL_DIFF && Math.abs(v - at(x, y + 1)) <= FLAT_PIXEL_DIFF) flat++
        }
        if (flat >= MAX_FLAT_FRACTION * (GRID - 2) * (GRID - 2)) return Verdict.FLAT

        val content = (bottom - top) * (right - left)
        if (content < MIN_CONTENT_FRACTION * GRID * GRID) return Verdict.MOSTLY_EMPTY
        return Verdict.OK
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

/**
 * The node tree and the screenshot are not read at the same instant. During a
 * scroll, an animation or an app switch, bounds read after the screenshot can point
 * at different pixels — crops that cut across UI and whatever was on screen
 * before (seen on RICO as half-photo/half-UI crops). Only regions whose bounds are
 * identical in a read **before** the screenshot and one **after** it, in the same
 * app, are classified. Pure Kotlin.
 */
object RegionStability {
    fun stable(beforePackage: String?, before: List<ImageRegion>, afterPackage: String?, after: List<ImageRegion>): List<ImageRegion> {
        if (beforePackage == null || beforePackage != afterPackage) return emptyList()
        val boxes = before.map { it.box }.toHashSet()
        return after.filter { it.box in boxes }
    }
}

/**
 * Tracks window-state changes for the scanner:
 *  - the region pass is skipped for [settleMs] after any (non-overlay) window change,
 *    while activity/app transitions animate and the tree and screenshot disagree;
 *  - [onWindowStateChanged] returns true when the *app* in front changed, so the
 *    caller resets the confirmer — two positives must not combine across apps (a
 *    positive from the previous app plus one transition frame used to confirm a
 *    detection attributed to the new app).
 * Pure Kotlin with injected timestamps; not thread-safe (scanner worker thread).
 */
class AppSwitchTracker(private val settleMs: Long = ScanConfig.REGION_SETTLE_MS) {
    var foregroundPackage: String? = null
        private set
    private var lastChangeAtMs: Long? = null

    /** Records a window-state change of [pkg] at [nowMs]; returns true if the foreground app changed. */
    fun onWindowStateChanged(pkg: String?, nowMs: Long, overlayPackages: Set<String>): Boolean {
        if (pkg.isNullOrEmpty() || pkg in overlayPackages) return false
        lastChangeAtMs = nowMs
        val changed = foregroundPackage != null && foregroundPackage != pkg
        foregroundPackage = pkg
        return changed
    }

    /** False within [settleMs] of the last window change. */
    fun isSettled(nowMs: Long): Boolean = lastChangeAtMs?.let { nowMs - it >= settleMs } ?: true
}
