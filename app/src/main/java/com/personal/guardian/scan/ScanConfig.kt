package com.personal.guardian.scan

/**
 * Stage 3 (image) and Stage 4 (text) — tunable constants for screen scanning, kept
 * in one place.
 */
object ScanConfig {

    /**
     * Baseline capture interval while the service is active (spec: 6–8 s).
     * Must stay comfortably below [CONFIRMATION_WINDOW_MS]: two consecutive baseline
     * frames are never closer than this (plus timer/classification jitter), so if
     * it approached the window, apps outside [WATCHED_PACKAGES] could never confirm.
     */
    const val BASELINE_INTERVAL_MS = 6_000L

    /** Faster capture interval while a watched app is in the foreground (spec: 1–2 s). */
    const val FAST_INTERVAL_MS = 1_500L

    /**
     * Minimum gap between two screenshots. Android rejects takeScreenshot() calls
     * made less than ~1 s apart (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT).
     */
    const val MIN_CAPTURE_GAP_MS = 1_000L

    /**
     * Apps whose foreground presence switches capture to [FAST_INTERVAL_MS].
     * Edit freely; matching is on the exact package name.
     */
    val WATCHED_PACKAGES: Set<String> = setOf(
        // Messaging
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",   // Telegram X
        // Browsers
        "com.android.chrome",
        "com.chrome.beta",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.focus",
        "com.sec.android.app.sbrowser", // Samsung Internet
        "com.microsoft.emmx",           // Edge
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.UCMobile.intl",
        "com.mi.globalbrowser",
        "com.android.browser",
    )

    /**
     * Packages whose windows sit *on top of* the real foreground app (status bar,
     * notification shade, volume dialog…). Their window-state events are ignored so
     * pulling down the shade over WhatsApp does not drop out of fast mode. The
     * service adds the enabled keyboards (IMEs) to this at runtime.
     */
    val OVERLAY_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
    )

    /**
     * Threshold on the frame's signal ([NsfwScores.signal] = sexy + porn + hentai
     * probability from the GantMan 5-class model, 0..1) at or above which a frame
     * counts as positive. For suggestive photos the signal is essentially the "sexy"
     * class; explicit content moves to porn/hentai and still counts.
     *
     * Starting point 0.3, chosen for maximum sensitivity: in testing, ordinary photos
     * and phone-screen layouts scored median ~0.01 and at most ~0.19 (textures and
     * logos, mostly "hentai"/"drawings" noise), so 0.3 sits just above that, while a
     * frame only needs ~30% combined sexy/porn probability — well below "sexy is the
     * most likely class". Lower (e.g. 0.2) for more sensitivity; tune with the
     * per-class scores from [LOG_EVERY_FRAME_SCORE].
     */
    const val NSFW_THRESHOLD = 0.3f

    /**
     * TEMPORARY calibration aid: when true, every classified frame's raw score is
     * written to GuardianLog, not just confirmed detections. Set to false (or delete
     * this flag and its single use in GuardianAccessibilityService) once the
     * threshold is calibrated. Note: in fast mode this adds ~40 log lines a minute,
     * so the 1 MiB event log rotates within a few hours of heavy use.
     */
    const val LOG_EVERY_FRAME_SCORE = true

    /**
     * Positive frames required for a confirmed detection. They need not be
     * consecutive: negative frames in between don't reset the count.
     */
    const val CONFIRMATION_COUNT = 2

    /**
     * At least [CONFIRMATION_COUNT] positive frames must fall within this rolling
     * window. 7 s bounds time-to-detection in fast mode (1.5 s frames).
     */
    const val CONFIRMATION_WINDOW_MS = 7_000L

    /**
     * After a detection is reported, further detections of the *same on-screen
     * content* are suppressed for this long (no notification, thumbnail, log entry
     * or event). Different content is still reported immediately.
     */
    const val DETECTION_COOLDOWN_MS = 60_000L

    /**
     * Two frames count as the same content when their 64-bit screen fingerprints
     * (dHash) differ in at most this many bits. ~10 tolerates the clock ticking and
     * small scrolls; a different image or page differs far more.
     */
    const val SAME_CONTENT_MAX_DISTANCE = 10

    /** Longest edge of the saved review thumbnail, in pixels. */
    const val THUMBNAIL_MAX_DIM = 256

    /** JPEG quality for review thumbnails. */
    const val THUMBNAIL_JPEG_QUALITY = 70

    /** Keep at most this many review thumbnails on disk (oldest deleted first). */
    const val MAX_SAVED_THUMBNAILS = 100

    // ---- Stage 3: region scanning (README "Stage 3 — region scanning") ----

    /**
     * Second detection path alongside the whole-screen pass: image-bearing elements
     * found in the accessibility node tree are cropped out of the same screenshot and
     * classified at their own resolution.
     */
    const val REGION_SCAN_ENABLED = true

    /**
     * Threshold on a region's signal. Same as [NSFW_THRESHOLD] by default, so an image
     * counts the same whether it fills the screen or sits in a chat bubble. Measured
     * on 400 everyday COCO photos of people shown as a chat image: 54 (13.5%) reach
     * 0.3 as regions (vs 3 in the whole-screen pass) — the model scores some sports
     * and family photos high, which a full-screen view already triggers today.
     * Raise this (0.5: 42, 0.7: 24, 0.9: 14 of 400) to trade sensitivity for fewer
     * alerts on ordinary photos.
     */
    const val REGION_THRESHOLD = NSFW_THRESHOLD

    /** At most this many regions classified per capture: the largest qualifying ones. */
    const val REGION_MAX_PER_CAPTURE = 3

    /** Regions whose shorter side is below this (dp) are skipped: avatars, icons, emoji. */
    const val REGION_MIN_SIDE_DP = 64

    /** Regions covering more than this fraction of the screen are left to the whole-screen pass. */
    const val REGION_MAX_SCREEN_FRACTION = 0.6f

    /** Regions longer than this ratio (long side / short side) are skipped: banners, strips. */
    const val REGION_MAX_ASPECT = 3f

    /** Upper bound on nodes visited per capture while looking for regions. */
    const val REGION_MAX_NODES = 1_500

    /**
     * Per-capture time budget for region work: once exceeded, the remaining regions
     * of that capture are skipped (logged in the cost summary), so a slow device
     * can't fall behind the capture interval ([FAST_INTERVAL_MS]).
     */
    const val REGION_TIME_BUDGET_MS = 400L

    /** Recently classified regions remembered by content (unchanged stickers aren't re-classified). */
    const val REGION_CACHE_SIZE = 32

    /** A pipeline-cost summary line (whole vs region time, cache hits) is logged every this many captures. */
    const val COST_LOG_EVERY_CAPTURES = 200

    // ---- Stage 4: text scanning ----

    /** Bundled keyword/phrase list (see README "Stage 4"); editable without code changes. */
    const val KEYWORD_ASSET = "text/keywords.txt"

    /**
     * Content-changed events come in bursts (typing, scrolling, new messages); the
     * first one schedules a single text check this much later and the rest of the
     * burst is absorbed.
     */
    const val TEXT_CHECK_DEBOUNCE_MS = 750L

    /** Upper bounds per text check, so a huge web page can't stall the scanner. */
    const val TEXT_MAX_NODES = 2_000
    const val TEXT_MAX_CHARS = 50_000

    /**
     * Same-content cooldown for text detections, matching image detections: the same
     * matched term in the same app is reported at most once per this period.
     */
    const val TEXT_COOLDOWN_MS = DETECTION_COOLDOWN_MS

    /** Characters of context kept either side of a match in the review-log snippet. */
    const val TEXT_SNIPPET_RADIUS = 40

    /**
     * Bundled TFLite model: GantMan nsfw_model, MobileNetV2 140 224, 5 classes
     * (see README "Stage 3" for source, license and checksum).
     */
    const val MODEL_ASSET = "models/nsfw_mobilenet_v2_140_224.tflite"
}
