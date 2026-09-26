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
data class ImageRegion(val box: Box, val kind: RegionKind, val className: String) {
    /** Short label for logs and detection metadata, e.g. `image 540x540@480,900 (ImageView)`. */
    val label: String get() = "${kind.label} $box (${className.substringAfterLast('.')})"
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
    private val MEDIA_HINTS = listOf(
        "image", "img", "photo", "picture", "sticker", "thumb", "media", "video", "gif", "player", "preview",
        "صورة", "صوره", "ملصق", "فيديو"
    )
    /** Containers that are never an image themselves (their children are still walked). */
    private val CONTAINER_CLASSES = listOf("WebView", "RecyclerView", "ListView", "ScrollView", "ViewPager", "Layout")

    /**
     * What kind of image-bearing element a node is, or null. Class names come from
     * `AccessibilityNodeInfo.getClassName` — the platform base class for most custom
     * views (AppCompatImageView, Fresco's DraweeView → `android.widget.ImageView`).
     */
    fun kindOf(className: CharSequence?, viewId: String?, contentDescription: CharSequence?): RegionKind? {
        val cls = className?.toString().orEmpty()
        val simple = cls.substringAfterLast('.')
        if (simple.endsWith("ImageView") || cls == "android.widget.Image" || simple == "ImageButton") return RegionKind.IMAGE
        if (VIDEO_CLASSES.any { simple.contains(it) }) return RegionKind.VIDEO
        if (CONTAINER_CLASSES.any { simple.contains(it) }) return null
        val id = viewId?.substringAfter(":id/")?.lowercase(Locale.ROOT).orEmpty()
        val desc = contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (MEDIA_HINTS.any { id.contains(it) || desc.contains(it) }) return RegionKind.MEDIA_HINT
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
                if (!box.isEmpty) candidates += ImageRegion(box, kind, node.className?.toString().orEmpty())
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

    private val map = object : LinkedHashMap<Key, NsfwScores>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, NsfwScores>?) = size > capacity
    }

    val size: Int get() = map.size

    fun get(hash: Long, width: Int, height: Int): NsfwScores? = map[Key(hash, width, height)]

    fun put(hash: Long, width: Int, height: Int, scores: NsfwScores) {
        map[Key(hash, width, height)] = scores
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

    /** Records one capture; returns a summary line every [reportEvery] captures (then resets), else null. */
    fun record(
        wholeMs: Long, regionMs: Long, regionsFound: Int, classified: Int, cacheHits: Int, budgetSkips: Int
    ): String? {
        captures++
        this.wholeMs += wholeMs
        this.regionMs += regionMs
        this.regionsFound += regionsFound
        this.classified += classified
        this.cacheHits += cacheHits
        this.budgetSkips += budgetSkips
        if (captures < reportEvery) return null
        val n = captures.toDouble()
        val line = String.format(
            Locale.US,
            "Scan cost (last %d captures): whole-screen %.1f ms avg, regions %.1f ms avg " +
                "(%.2f regions/capture: %d classified, %d cached, %d skipped by time budget)",
            captures, this.wholeMs / n, this.regionMs / n, this.regionsFound / n,
            this.classified, this.cacheHits, this.budgetSkips
        )
        reset()
        return line
    }

    private fun reset() {
        captures = 0; wholeMs = 0; regionMs = 0; regionsFound = 0; classified = 0; cacheHits = 0; budgetSkips = 0
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
        val scores: List<RegionScore>, val found: Int, val classified: Int, val cacheHits: Int, val budgetSkips: Int
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
        release: (B) -> Unit
    ): Pass {
        val scores = ArrayList<RegionScore>(regions.size)
        var classified = 0
        var cacheHits = 0
        var skipped = 0
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
                val hash = fingerprint(image)
                val cached = cache.get(hash, rect.width, rect.height)
                val result = cached ?: classify(image).also { cache.put(hash, rect.width, rect.height, it) }
                if (cached != null) cacheHits++ else classified++
                scores += RegionScore(region, rect, result, hash, cached = cached != null)
            } finally {
                release(image)
            }
        }
        return Pass(scores, regions.size, classified, cacheHits, skipped)
    }
}
