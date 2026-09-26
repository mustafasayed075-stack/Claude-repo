package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for Stage 3 region scanning: which nodes count as image-bearing,
 * filtering / de-duplication / the per-capture cap, crop mapping, the score cache,
 * the per-capture scorer (cache + time budget), combining region and whole-screen
 * verdicts, and the cost summary.
 */
class ImageRegionsTest {

    /** Fake accessibility node. */
    private class Node(
        override val className: CharSequence?,
        val bounds: Box,
        override val viewId: String? = null,
        override val contentDescription: CharSequence? = null,
        override val isVisibleToUser: Boolean = true,
        val children: List<Node> = emptyList()
    ) : RegionNode {
        var released = false
        override val boundsInScreen: Box get() = bounds
        override val childCount: Int get() = children.size
        override fun child(index: Int): RegionNode = children[index]
        override fun release() { released = true }
        fun all(): List<Node> = listOf(this) + children.flatMap { it.all() }
    }

    private val screen = Box(0, 0, 1080, 2400)
    /** 64 dp at density 2.75. */
    private val limits = ImageRegionFinder.Limits(minSidePx = 176, maxScreenFraction = 0.6f, maxAspect = 3f, maxRegions = 3, maxNodes = 1_500)

    private fun box(x: Int, y: Int, w: Int, h: Int) = Box(x, y, x + w, y + h)
    private fun image(b: Box, cls: String = "android.widget.ImageView", id: String? = null, desc: String? = null, visible: Boolean = true) =
        Node(cls, b, id, desc, visible)
    private fun root(vararg children: Node) =
        Node("android.widget.FrameLayout", screen, children = listOf(Node("androidx.recyclerview.widget.RecyclerView", screen, children = children.toList())))

    private fun scores(signal: Float) = NsfwScores(drawings = 0f, hentai = 0f, neutral = 1f - signal, porn = 0f, sexy = signal)

    // ---- which nodes are image-bearing ----

    @Test
    fun imageAndVideoViewsAreRecognisedByClassName() {
        for (cls in listOf("android.widget.ImageView", "androidx.appcompat.widget.AppCompatImageView",
                "com.facebook.drawee.view.SimpleDraweeView".replace("SimpleDraweeView", "GifImageView"), "android.widget.Image",
                "android.widget.ImageButton")) {
            assertEquals(cls, RegionKind.IMAGE, ImageRegionFinder.kindOf(cls, null, null))
        }
        for (cls in listOf("android.widget.VideoView", "android.view.SurfaceView", "android.view.TextureView",
                "com.google.android.exoplayer2.ui.StyledPlayerView", "androidx.media3.ui.PlayerView")) {
            assertEquals(cls, RegionKind.VIDEO, ImageRegionFinder.kindOf(cls, null, null))
        }
    }

    @Test
    fun otherViewsCountOnlyWithAMediaHintAndContainersNever() {
        assertEquals(RegionKind.MEDIA_HINT, ImageRegionFinder.kindOf("android.view.View", null, "Sticker"))
        assertEquals(RegionKind.MEDIA_HINT, ImageRegionFinder.kindOf("android.view.View", null, "ملصق"))
        assertEquals(RegionKind.MEDIA_HINT, ImageRegionFinder.kindOf("android.view.View", "com.whatsapp:id/sticker_image", null))
        assertEquals(RegionKind.MEDIA_HINT, ImageRegionFinder.kindOf("android.view.View", "org.telegram:id/photo_preview", null))
        assertNull(ImageRegionFinder.kindOf("android.widget.TextView", "com.whatsapp:id/message_text", "Hello"))
        assertNull(ImageRegionFinder.kindOf("android.webkit.WebView", null, "Photo gallery"))
        assertNull(ImageRegionFinder.kindOf("android.widget.FrameLayout", "com.whatsapp:id/media_container", null))
        assertNull(ImageRegionFinder.kindOf(null, null, null))
    }

    // ---- filtering, de-duplication, cap ----

    @Test
    fun chatScreenYieldsStickerAndPhotoButNotAvatarsBannersOrFullScreenVideo() {
        val sticker = image(box(560, 900, 480, 480), id = "com.whatsapp:id/sticker")
        val photo = image(box(340, 1500, 700, 525))
        val tree = root(
            image(box(20, 120, 132, 132), id = "com.whatsapp:id/avatar"),   // too small (avatar)
            image(box(0, 300, 1080, 200)),                                  // banner: aspect 5.4
            Node("android.view.TextureView", screen),                       // full-screen video: whole pass covers it
            image(box(40, 2100, 300, 300), visible = false),               // not visible
            Node("android.widget.TextView", box(40, 700, 600, 180), contentDescription = "hi"),
            sticker, photo
        )
        val found = ImageRegionFinder.collect(tree, screen, limits)
        assertEquals(listOf(photo.bounds, sticker.bounds), found.map { it.box }) // largest first
        assertTrue(found.all { it.kind == RegionKind.IMAGE })
    }

    @Test
    fun smallNonFullScreenPlayerIsARegion() {
        val player = Node("android.view.SurfaceView", box(0, 300, 1080, 608))
        val found = ImageRegionFinder.collect(root(player), screen, limits)
        assertEquals(RegionKind.VIDEO, found.single().kind)
        assertEquals("video 1080x608@0,300 (SurfaceView)", found.single().label)
    }

    @Test
    fun mediaContainerAroundItsImageIsOneRegionTheTighterBox() {
        val inner = image(box(350, 1510, 680, 505))
        val outer = Node("android.view.View", box(340, 1500, 700, 525), viewId = "x:id/photo_frame", children = listOf(inner))
        val found = ImageRegionFinder.collect(root(outer), screen, limits)
        assertEquals(inner.bounds, found.single().box)
        assertEquals(RegionKind.IMAGE, found.single().kind)
    }

    @Test
    fun separateImagesInsideOneBigHintedViewAreKept() {
        val a = image(box(100, 500, 400, 400))
        val b = image(box(560, 500, 400, 400))
        val grid = Node("android.view.View", box(80, 480, 900, 440), viewId = "x:id/media_grid", children = listOf(a, b))
        val found = ImageRegionFinder.collect(root(grid), screen, limits)
        assertEquals(setOf(grid.bounds, a.bounds, b.bounds), found.map { it.box }.toSet())
    }

    @Test
    fun atMostTheNLargestRegionsAreReturned() {
        val sizes = listOf(200, 500, 300, 450, 250)
        val nodes = sizes.mapIndexed { i, s -> image(box(0, 10 + i * 460, s, s)) }
        val found = ImageRegionFinder.collect(root(*nodes.toTypedArray()), screen, limits)
        assertEquals(listOf(500, 450, 300), found.map { it.box.width })
    }

    @Test
    fun partlyOffScreenRegionIsClippedToTheScreen() {
        val found = ImageRegionFinder.collect(root(image(box(700, 2200, 600, 600))), screen, limits)
        assertEquals(Box(700, 2200, 1080, 2400), found.single().box)
    }

    @Test
    fun walkReleasesChildrenAndRespectsNodeLimit() {
        val nodes = (0 until 50).map { image(box(0, it * 40, 300, 300)) }
        val tree = root(*nodes.toTypedArray())
        ImageRegionFinder.collect(tree, screen, limits.copy(maxNodes = 10))
        assertTrue("visited nodes released", tree.all().drop(1).take(9).all { it.released })
        assertFalse("nodes past the limit untouched", nodes.last().released)
        assertFalse("root is the caller's to release", tree.released)
    }

    @Test
    fun invisibleSubtreesAreSkipped() {
        val hiddenChild = image(box(100, 100, 400, 400))
        val hidden = Node("android.view.ViewGroup", box(0, 0, 1080, 1000), isVisibleToUser = false, children = listOf(hiddenChild))
        assertTrue(ImageRegionFinder.collect(root(hidden), screen, limits).isEmpty())
    }

    @Test
    fun walkBenchmarkAtTheNodeLimit() {
        // Worst case: a tree at REGION_MAX_NODES (fake nodes, so this measures the
        // walk/filter logic; on a device, fetching nodes over binder adds to it and is
        // included in the logged "regions" time).
        fun level(depth: Int, y: Int): Node =
            if (depth == 0) image(box(0, y % 2000, 300 + y % 400, 300 + y % 400))
            else Node("android.view.ViewGroup", screen, children = (0 until 6).map { level(depth - 1, y * 6 + it) })
        val tree = Node("android.widget.FrameLayout", screen, children = (0 until 2).map { level(4, it) }) // 2 * (6^4 + ...) ≈ 3,100 nodes
        repeat(20) { ImageRegionFinder.collect(tree, screen, limits) } // warm-up
        val rounds = 200
        val t0 = System.nanoTime()
        var found = 0
        repeat(rounds) { found += ImageRegionFinder.collect(tree, screen, limits).size }
        val avgMs = (System.nanoTime() - t0) / 1_000_000.0 / rounds
        println("ImageRegionsTest: walk of ${ScanConfig.REGION_MAX_NODES}-node limit: avg ${"%.3f".format(avgMs)} ms/capture")
        assertEquals(3 * rounds, found)
        assertTrue("walk should be far below the capture interval, was $avgMs ms", avgMs < 50)
    }

    // ---- crop mapping ----

    @Test
    fun cropMapsScreenBoxIntoTheScreenshot() {
        val b = box(560, 900, 480, 480)
        assertEquals(b, RegionCrop.cropRect(b, 1080, 2400, 1080, 2400))
        // A half-resolution screenshot halves the crop.
        assertEquals(Box(280, 450, 520, 690), RegionCrop.cropRect(b, 1080, 2400, 540, 1200))
        // Clipped to the bitmap; null when too little is left or dimensions are invalid.
        assertEquals(Box(1000, 2300, 1080, 2400), RegionCrop.cropRect(Box(1000, 2300, 1200, 2600), 1080, 2400, 1080, 2400))
        assertNull(RegionCrop.cropRect(Box(1000, 2300, 1200, 2600), 1080, 2400, 1080, 2400, minSidePx = 176))
        assertNull(RegionCrop.cropRect(Box(2000, 0, 2100, 100), 1080, 2400, 1080, 2400))
        assertNull(RegionCrop.cropRect(b, 0, 2400, 1080, 2400))
    }

    // ---- score cache ----

    @Test
    fun cacheHitsOnlyOnSameHashAndSizeAndEvictsLeastRecentlyUsed() {
        val cache = RegionScoreCache(capacity = 2)
        cache.put(1L, 480, 480, scores(0.9f))
        assertEquals(0.9f, cache.get(1L, 480, 480)!!.signal, 1e-6f)
        assertNull("same hash, different size", cache.get(1L, 480, 481))
        assertNull(cache.get(2L, 480, 480))
        cache.put(2L, 10, 10, scores(0.1f))
        cache.get(1L, 480, 480) // 1 is now most recent
        cache.put(3L, 10, 10, scores(0.2f))
        assertNotNull(cache.get(1L, 480, 480))
        assertNull("least recently used evicted", cache.get(2L, 10, 10))
        assertEquals(2, cache.size)
    }

    // ---- per-capture scorer ----

    private class FakeImage(val rect: Box) { var released = false }

    private fun regions(vararg boxes: Box) = boxes.map { ImageRegion(it, RegionKind.IMAGE, "android.widget.ImageView") }

    @Test
    fun unchangedStickerIsClassifiedOnceAcrossCaptures() {
        val cache = RegionScoreCache()
        val scorer = RegionScorer(cache, budgetMs = 400) { 0L }
        var modelRuns = 0
        val images = ArrayList<FakeImage>()
        fun capture(): RegionScorer.Pass = scorer.score(
            regions(box(560, 900, 480, 480)), 1080, 2400, 176,
            crop = { FakeImage(it).also { img -> images += img } },
            fingerprint = { 42L },
            classify = { modelRuns++; scores(0.9f) },
            release = { it.released = true }
        )
        val first = capture()
        assertEquals(1, first.classified)
        assertFalse(first.scores.single().cached)
        repeat(4) {
            val p = capture()
            assertEquals(0, p.classified)
            assertEquals(1, p.cacheHits)
            assertEquals(0.9f, p.scores.single().scores.signal, 1e-6f)
        }
        assertEquals("model ran once for 5 captures", 1, modelRuns)
        assertTrue("every crop released", images.all { it.released })
    }

    @Test
    fun timeBudgetSkipsRemainingRegions() {
        var now = 0L
        val scorer = RegionScorer(RegionScoreCache(), budgetMs = 400) { now }
        val pass = scorer.score(
            regions(box(0, 0, 500, 500), box(0, 600, 400, 400), box(0, 1100, 300, 300)), 1080, 2400, 176,
            crop = { FakeImage(it) },
            fingerprint = { it.rect.top.toLong() },
            classify = { now += 250; scores(0.1f) }, // a slow device: 250 ms per region
            release = {}
        )
        assertEquals(2, pass.classified)
        assertEquals(1, pass.budgetSkips)
        assertEquals(3, pass.found)
    }

    @Test
    fun cropsSmallerThanTheMinimumAfterClippingAreSkipped() {
        val pass = RegionScorer(RegionScoreCache()) { 0L }.score(
            regions(Box(1000, 2300, 1200, 2600)), 1080, 2400, 176,
            crop = { FakeImage(it) }, fingerprint = { 1L }, classify = { scores(1f) }, release = {}
        )
        assertTrue(pass.scores.isEmpty())
        assertEquals(0, pass.classified)
    }

    // ---- combining whole-screen and region verdicts ----

    private fun regionScore(signal: Float) =
        RegionScore(regions(box(560, 900, 480, 480)).single(), box(560, 900, 480, 480), scores(signal), 7L, cached = false)

    @Test
    fun smallStickerMakesTheFramePositiveWhenTheWholeScreenDoesNot() {
        val v = FrameVerdict.combine(scores(0.05f), listOf(regionScore(0.1f), regionScore(0.92f)), 0.3f, 0.3f)
        assertTrue(v.positive)
        assertEquals(0.92f, v.score, 1e-6f)
        assertNotNull(v.region)
    }

    @Test
    fun wholeScreenStillDecidesWhenItScoresHigherOrThereAreNoRegions() {
        val v = FrameVerdict.combine(scores(0.95f), listOf(regionScore(0.5f)), 0.3f, 0.3f)
        assertTrue(v.positive)
        assertNull(v.region)
        assertEquals(0.95f, v.score, 1e-6f)
        val none = FrameVerdict.combine(scores(0.4f), emptyList(), 0.3f, 0.3f)
        assertTrue(none.positive)
        assertNull(none.region)
    }

    @Test
    fun negativeFrameReportsTheHighestSignal() {
        val v = FrameVerdict.combine(scores(0.05f), listOf(regionScore(0.2f)), 0.3f, 0.3f)
        assertFalse(v.positive)
        assertEquals(0.2f, v.score, 1e-6f)
    }

    @Test
    fun regionThresholdIsIndependent() {
        assertFalse(FrameVerdict.combine(scores(0.05f), listOf(regionScore(0.6f)), 0.3f, 0.7f).positive)
        assertTrue(FrameVerdict.combine(scores(0.05f), listOf(regionScore(0.75f)), 0.3f, 0.7f).positive)
    }

    @Test
    fun defaultRegionThresholdMatchesTheWholeScreenOne() {
        assertEquals(ScanConfig.NSFW_THRESHOLD, ScanConfig.REGION_THRESHOLD, 0f)
    }

    @Test
    fun confirmerAcceptsTheCombinedVerdict() {
        val c = DetectionConfirmer(threshold = 0.8f, requiredPositives = 2, windowMs = 7_000)
        // Region-positive frames (own threshold) confirm even though the reported score is below the whole-screen threshold.
        assertNull(c.onFrame(0.5f, 0, TriggerSource.EVENT, positive = true))
        assertNotNull(c.onFrame(0.5f, 1_500, TriggerSource.EVENT, positive = true))
        // Default still uses the threshold.
        assertNull(c.onFrame(0.9f, 10_000, TriggerSource.EVENT))
        assertNull(c.onFrame(0.5f, 11_000, TriggerSource.EVENT))
    }

    // ---- logging ----

    @Test
    fun regionSummaryAndFrameLine() {
        val cached = regionScore(0.912f).copy(cached = true)
        assertEquals("0", ScanLog.regionSummary(emptyList()))
        assertEquals("1 [0.912 image 480x480@560,900 (ImageView)*]", ScanLog.regionSummary(listOf(cached)))
        val line = ScanLog.frameLine(scores(0.912f), 0.3f, TriggerSource.EVENT, "com.whatsapp", 1, 2, ScanLog.regionSummary(listOf(cached)))
        assertTrue(line, line.endsWith("positives=1/2 regions=1 [0.912 image 480x480@560,900 (ImageView)*]"))
        assertFalse(ScanLog.frameLine(scores(0.1f), 0.3f, TriggerSource.EVENT, null, 0, 2).contains("regions="))
    }

    @Test
    fun costStatsReportEveryNCaptures() {
        val stats = ScanCostStats(reportEvery = 3)
        assertNull(stats.record(40, 0, 0, 0, 0, 0))
        assertNull(stats.record(40, 60, 2, 1, 1, 0))
        val line = stats.record(40, 90, 1, 1, 0, 1)
        assertEquals(
            "Scan cost (last 3 captures): whole-screen 40.0 ms avg, regions 50.0 ms avg " +
                "(1.00 regions/capture: 2 classified, 1 cached, 1 skipped by time budget)",
            line
        )
        assertEquals(0, stats.captures)
    }

    @Test
    fun detectionMetadataNamesTheRegion() {
        val json = DetectionEvent(1_790_000_000_123L, 0.9f, TriggerSource.EVENT, "com.whatsapp", "t.jpg",
            region = "image 480x480@560,900 (ImageView)").toJsonLine()
        assertTrue(json, json.endsWith(",\"thumbnail\":\"t.jpg\",\"region\":\"image 480x480@560,900 (ImageView)\"}"))
    }
}
