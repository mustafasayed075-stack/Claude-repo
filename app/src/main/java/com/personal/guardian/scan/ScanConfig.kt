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
