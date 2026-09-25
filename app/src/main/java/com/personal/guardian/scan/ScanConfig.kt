package com.personal.guardian.scan

/**
 * Stage 3 — tunable constants for screen scanning, kept in one place.
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
     * Classifier score (OpenNSFW "nsfw" probability, 0..1) at or above which a frame
     * counts as positive. Yahoo's guidance: < 0.2 very likely safe, > 0.8 very
     * likely NSFW.
     *
     * Set to 0.2 — deliberately at the bottom of Yahoo's "likely safe" band — for
     * maximum sensitivity to any suggestive / skin-exposure content (chosen after
     * on-device testing). Tradeoff, accepted for this use case: frequent false
     * positives on ordinary photos (beach, sports, fitness, close-up portraits,
     * skin-toned backgrounds). The 2-positives-within-the-window rule is the only
     * filter left against one-off spikes. Tune using the per-frame scores from
     * [LOG_EVERY_FRAME_SCORE].
     */
    const val NSFW_THRESHOLD = 0.2f

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

    /** Bundled TFLite model (see README "Stage 3" for source and license). */
    const val MODEL_ASSET = "models/open_nsfw.tflite"
}
