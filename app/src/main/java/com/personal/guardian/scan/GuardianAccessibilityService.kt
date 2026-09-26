package com.personal.guardian.scan

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.personal.guardian.text.KeywordList
import com.personal.guardian.text.KeywordMatcher
import com.personal.guardian.text.TextExtractor
import com.personal.guardian.text.TextFingerprint
import com.personal.guardian.text.TextNode
import com.personal.guardian.text.TextScanTrigger
import com.personal.guardian.text.TextSnippet
import com.personal.guardian.util.GuardianLog
import com.personal.guardian.util.ProcessDiagnostics
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 3 — screen scanning via the Accessibility Service's one-shot screenshot API.
 *
 * Enabled once by the user (Settings → Accessibility → Installed apps → Guardian).
 * While active it:
 *  - captures the screen every [ScanConfig.BASELINE_INTERVAL_MS] (periodic trigger);
 *  - switches to [ScanConfig.FAST_INTERVAL_MS] as soon as a watched app
 *    ([ScanConfig.WATCHED_PACKAGES]) comes to the foreground, and back to the
 *    baseline when it leaves (event trigger);
 *  - classifies each frame on-device ([NsfwClassifier]) and feeds the score to
 *    [DetectionConfirmer]; confirmed detections are saved locally
 *    ([DetectionStore]), logged ([GuardianLog]), shown as a notification
 *    ([DetectionNotifier]) and published on [DetectionBus] for later stages.
 *
 * Stage 4 adds a second, independent path in the same service: on window-content
 * (and window-state) changes in a watched app, the visible text of the active
 * window's node tree is read (no OCR, no screenshot) and checked on-device by
 * [KeywordMatcher]; matches go through the same pipeline — cooldown, review log
 * (text snippet instead of a thumbnail), GuardianLog, notification, [DetectionBus].
 *
 * Detection and logging only — no lock/block action in these stages.
 *
 * `takeScreenshot()` needs Android 11 (API 30); on older versions image scanning
 * logs a warning and stays off, while text scanning still runs. All scanning state
 * lives on one worker thread; the accessibility callbacks (main thread) only post
 * to it.
 */
class GuardianAccessibilityService : AccessibilityService() {

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    // Worker-thread state.
    private val scheduler = CaptureScheduler()
    private val confirmer = DetectionConfirmer()
    private val cooldown = DetectionCooldown()
    private val fingerprintPixels = IntArray(ScreenFingerprint.WIDTH * ScreenFingerprint.HEIGHT)
    private var classifier: NsfwClassifier? = null
    private var captureInFlight = false
    private val lastFailureLogAt = HashMap<String, Long>()

    private val tick = Runnable { onTick() }

    // Stage 4: text scanning (worker-thread state, except the thread-safe trigger).
    private val textTrigger = TextScanTrigger(ScanConfig.WATCHED_PACKAGES)
    private val textCooldown = DetectionCooldown(cooldownMs = ScanConfig.TEXT_COOLDOWN_MS, maxDistance = 0)
    private var keywordMatcher: KeywordMatcher? = null
    private val textCheck = Runnable { runTextCheck() }

    /** Distinguishes service instances in the log (a new instance = a new bind). */
    private val instanceId = instanceCounter.incrementAndGet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScanStatus.connected = true
        // Lifecycle diagnostics: a young process here means Guardian's process was
        // restarted (the reason, if recorded by the system, is logged just after).
        GuardianLog.i(
            this,
            "Screen scanning accessibility service connected " +
                "(instance #$instanceId, pid ${android.os.Process.myPid()}, " +
                "process age ${ProcessDiagnostics.processAgeSeconds()?.let { "${it}s" } ?: "unknown"}).",
            diagnostic = true
        )
        ProcessDiagnostics.logPreviousExitsIfNew(this)

        val thread = HandlerThread("guardian-screen-scan").also { it.start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        worker = handler

        // Stage 4: text scanning works on every supported Android version.
        handler.post { loadKeywordList() }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ScanStatus.unsupported = true
            GuardianLog.w(
                this,
                "Screen scanning unsupported on this Android version (API ${Build.VERSION.SDK_INT}); " +
                    "image scanning requires Android 11 (API 30)+ and stays off. Text scanning remains active."
            )
            return
        }

        scheduler.overlayPackages = ScanConfig.OVERLAY_PACKAGES + enabledKeyboardPackages()
        val initialForeground = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

        handler.post {
            classifier = try {
                NsfwClassifier.create(applicationContext)
            } catch (t: Throwable) {
                GuardianLog.e(applicationContext, "Screen scanning: failed to load on-device model; scanning disabled.", t)
                null
            }
            ScanStatus.modelLoaded = classifier != null
            if (classifier == null) return@post
            scheduler.onForegroundChanged(initialForeground)
            ScanStatus.fastModePackage = if (scheduler.isFastMode) scheduler.foregroundPackage else null
            GuardianLog.i(
                applicationContext,
                "Screen scanning active: baseline every ${scheduler.baselineIntervalMs} ms, " +
                    "fast every ${scheduler.fastIntervalMs} ms for ${ScanConfig.WATCHED_PACKAGES.size} watched apps " +
                    "(threshold ${ScanConfig.NSFW_THRESHOLD}, confirm ${ScanConfig.CONFIRMATION_COUNT} " +
                    "in ${ScanConfig.CONFIRMATION_WINDOW_MS} ms)."
            )
            handler.post(tick)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        val pkg = event.packageName?.toString()
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            worker?.post { onForegroundChanged(pkg) }
        }
        // Stage 4: content/state change in a watched app → one debounced text check.
        if (textTrigger.onEvent(type, pkg)) {
            val handler = worker
            if (handler == null || !handler.postDelayed(textCheck, ScanConfig.TEXT_CHECK_DEBOUNCE_MS)) {
                textTrigger.onCheckStarted()
            }
        }
    }

    override fun onInterrupt() {
        // No spoken/haptic feedback to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The system only unbinds a running accessibility service when it is no
        // longer enabled/allowed (Settings toggle, force-stop, device policy), on a
        // package update, a user switch, or while UI automation suppresses services.
        // Logging whether it is still enabled tells those cases apart.
        GuardianLog.w(
            this,
            "Screen scanning accessibility service unbound by system (instance #$instanceId; " +
                "still enabled in Settings: ${yesNo(isEnabledInSettings(this))}; screen on: ${yesNo(isScreenOn())}).",
            diagnostic = true
        )
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GuardianLog.i(this, "Screen scanning accessibility service destroyed (instance #$instanceId).", diagnostic = true)
        shutdown()
        super.onDestroy()
    }

    // ---- worker thread ----

    private fun onForegroundChanged(pkg: String) {
        if (classifier == null) return
        if (!scheduler.onForegroundChanged(pkg)) return
        val handler = worker ?: return
        val now = SystemClock.elapsedRealtime()
        if (scheduler.isFastMode) {
            ScanStatus.fastModePackage = pkg
            GuardianLog.i(applicationContext, "Screen scan: fast capture ON ($pkg in foreground, every ${scheduler.fastIntervalMs} ms).")
        } else {
            ScanStatus.fastModePackage = null
            GuardianLog.i(applicationContext, "Screen scan: fast capture OFF ($pkg in foreground), back to every ${scheduler.baselineIntervalMs} ms.")
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, scheduler.delayAfterModeChange(now))
    }

    private fun onTick() {
        val handler = worker ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) captureIfPossible()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, scheduler.delayUntilNextCapture(SystemClock.elapsedRealtime()))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureIfPossible() {
        val handler = worker ?: return
        if (classifier == null || captureInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (!isScreenOn()) {
            // Nothing visible: don't capture, and don't let positives span a screen-off.
            confirmer.reset()
            scheduler.onCaptured(now) // keep the cadence without spinning
            return
        }

        val source = scheduler.currentSource
        scheduler.onCaptured(now)
        captureInFlight = true
        val onWorker = Executor { handler.post(it) }
        takeScreenshot(Display.DEFAULT_DISPLAY, onWorker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    processScreenshot(result, source)
                } catch (t: Throwable) {
                    logFailureRateLimited("processing", "Screen scan: frame processing failed.", t)
                } finally {
                    captureInFlight = false
                }
            }

            override fun onFailure(errorCode: Int) {
                captureInFlight = false
                logFailureRateLimited(
                    "screenshot-$errorCode",
                    "Screen scan: takeScreenshot failed (${screenshotErrorName(errorCode)}); skipping frame.",
                    null
                )
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processScreenshot(result: ScreenshotResult, source: TriggerSource) {
        val model = classifier ?: run {
            result.hardwareBuffer.close() // shutting down: just release the frame
            return
        }
        val frame: Bitmap = result.hardwareBuffer.use { buffer ->
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
            try {
                hw.copy(Bitmap.Config.ARGB_8888, false) ?: return
            } finally {
                hw.recycle()
            }
        }
        try {
            val scores = model.classify(frame)
            val score = scores.signal
            ScanStatus.onFrame(System.currentTimeMillis(), score, source)
            val confirmation = confirmer.onFrame(score, SystemClock.elapsedRealtime(), source)
            if (ScanConfig.LOG_EVERY_FRAME_SCORE) {
                // TEMPORARY calibration logging (see ScanConfig.LOG_EVERY_FRAME_SCORE).
                val positives = if (confirmation != null) confirmer.requiredPositives else confirmer.pendingPositives
                GuardianLog.i(
                    applicationContext,
                    ScanLog.frameLine(
                        scores, confirmer.threshold, source, scheduler.foregroundPackage,
                        positives, confirmer.requiredPositives
                    )
                )
            }
            if (confirmation != null) onConfirmed(confirmation, frame, scores)
        } finally {
            frame.recycle()
        }
    }

    private fun onConfirmed(confirmation: DetectionConfirmer.Confirmation, frame: Bitmap, scores: NsfwScores) {
        val ctx = applicationContext
        if (!cooldown.shouldReport(SystemClock.elapsedRealtime(), fingerprint(frame))) {
            // Same content as a detection reported within the cooldown: no reaction.
            ScanStatus.suppressedCount++
            return
        }
        val now = System.currentTimeMillis()
        val latest = confirmation.latest
        val thumbnail = DetectionStore.saveThumbnail(ctx, frame, now)
        val event = DetectionEvent(
            timestampMs = now,
            confidence = latest.score,
            source = latest.source,
            foregroundPackage = scheduler.foregroundPackage,
            thumbnailFile = thumbnail,
            classScores = scores
        )
        runCatching { DetectionStore.appendMetadata(ctx, event) }
            .onFailure { GuardianLog.e(ctx, "Screen scan: failed to write detection metadata.", it) }
        ScanStatus.confirmedCount++

        val frameSignals = confirmation.frames.joinToString { "%.3f".format(it.score) }
        GuardianLog.w(
            ctx,
            "CONFIRMED screen detection: signal=${"%.3f".format(latest.score)} (${scores.breakdown()}) trigger=${latest.source.label} " +
                "app=${event.foregroundPackage ?: "unknown"} frames=[$frameSignals] thumbnail=${thumbnail ?: "not saved"} " +
                "(same-content repeats suppressed since last report: ${cooldown.suppressedSinceLastReport})"
        )
        cooldown.resetSuppressedCount()
        if (!DetectionNotifier.show(ctx, event)) {
            GuardianLog.w(ctx, "Detection notification not shown: notifications are not permitted for Guardian.")
        }
        DetectionBus.publish(event).forEach {
            GuardianLog.e(ctx, "Detection listener failed.", it)
        }
    }

    // ---- Stage 4: text scanning (worker thread) ----

    private fun loadKeywordList() {
        keywordMatcher = try {
            val list = applicationContext.assets.open(ScanConfig.KEYWORD_ASSET)
                .bufferedReader(Charsets.UTF_8).use { KeywordList.parse(it) }
            KeywordMatcher(list).also {
                ScanStatus.textEntries = it.entryCount
                GuardianLog.i(
                    applicationContext,
                    "Text scanning active: ${list.entries.size} list entries, ${list.exceptions.size} exceptions " +
                        "(${ScanConfig.KEYWORD_ASSET}); watched apps only, on content changes."
                )
            }
        } catch (t: Throwable) {
            GuardianLog.e(applicationContext, "Text scanning: failed to load keyword list; text scanning disabled.", t)
            ScanStatus.textFailed = true
            null
        }
    }

    private fun runTextCheck() {
        textTrigger.onCheckStarted()
        val matcher = keywordMatcher ?: return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val pkg = root.packageName?.toString()
        try {
            // Only the watched apps' own windows (the event's package could differ from
            // what is now in front, e.g. after switching away within the debounce).
            if (!textTrigger.isWatched(pkg) || pkg == null) return
            val texts = TextExtractor.collect(
                AccessibilityTextNode(root), ScanConfig.TEXT_MAX_NODES, ScanConfig.TEXT_MAX_CHARS
            )
            ScanStatus.onTextCheck(System.currentTimeMillis())
            val found = texts.flatMap { t -> matcher.find(t).map { m -> t to m } }
            if (found.isNotEmpty()) onTextMatched(pkg, found)
        } catch (t: Throwable) {
            logFailureRateLimited("text-check", "Text scan: check failed.", t)
        } finally {
            releaseNode(root)
        }
    }

    private fun onTextMatched(pkg: String, found: List<Pair<String, KeywordMatcher.Match>>) {
        val ctx = applicationContext
        val terms = found.map { it.second.term }.distinct()
        val fingerprints = terms.map { TextFingerprint.of(pkg, it) }
        if (!textCooldown.shouldReportAny(SystemClock.elapsedRealtime(), fingerprints)) {
            // Same terms in the same app reported within the cooldown: no reaction.
            ScanStatus.textSuppressedCount++
            return
        }
        val (firstText, firstMatch) = found.first()
        val snippet = TextSnippet.around(firstText, firstMatch, ScanConfig.TEXT_SNIPPET_RADIUS)
        val event = DetectionEvent(
            timestampMs = System.currentTimeMillis(),
            confidence = 1f,
            source = TriggerSource.TEXT,
            foregroundPackage = pkg,
            thumbnailFile = null,
            kind = DetectionKind.TEXT,
            matchedTerms = terms,
            textSnippet = snippet
        )
        runCatching { DetectionStore.appendMetadata(ctx, event) }
            .onFailure { GuardianLog.e(ctx, "Text scan: failed to write detection metadata.", it) }
        ScanStatus.textDetectionCount++

        // The snippet goes to the review log only; the event log names the terms.
        GuardianLog.w(
            ctx,
            "CONFIRMED text detection: terms=[${terms.joinToString()}] app=$pkg matches=${found.size} " +
                "snippet=saved to review log " +
                "(same-content repeats suppressed since last report: ${textCooldown.suppressedSinceLastReport})"
        )
        textCooldown.resetSuppressedCount()
        if (!DetectionNotifier.show(ctx, event)) {
            GuardianLog.w(ctx, "Detection notification not shown: notifications are not permitted for Guardian.")
        }
        DetectionBus.publish(event).forEach {
            GuardianLog.e(ctx, "Detection listener failed.", it)
        }
    }

    /** [TextNode] over a live accessibility node (children are fetched lazily). */
    private class AccessibilityTextNode(private val node: AccessibilityNodeInfo) : TextNode {
        override val text: CharSequence? get() = node.text
        override val contentDescription: CharSequence? get() = node.contentDescription
        override val isVisibleToUser: Boolean get() = node.isVisibleToUser
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): TextNode? = node.getChild(index)?.let { AccessibilityTextNode(it) }
        override fun release() = releaseNode(node)
    }

    // ---- helpers ----

    private fun fingerprint(frame: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(frame, ScreenFingerprint.WIDTH, ScreenFingerprint.HEIGHT, true)
        try {
            small.getPixels(fingerprintPixels, 0, ScreenFingerprint.WIDTH, 0, 0, ScreenFingerprint.WIDTH, ScreenFingerprint.HEIGHT)
        } finally {
            if (small !== frame) small.recycle()
        }
        return ScreenFingerprint.dHash(fingerprintPixels)
    }

    private fun shutdown() {
        ScanStatus.connected = false
        ScanStatus.textEntries = 0
        ScanStatus.modelLoaded = false
        ScanStatus.fastModePackage = null
        val handler = worker ?: return
        worker = null
        handler.removeCallbacksAndMessages(null)
        handler.post {
            classifier?.close()
            classifier = null
            keywordMatcher = null
        }
        workerThread?.quitSafely()
        workerThread = null
    }

    private fun isScreenOn(): Boolean =
        ContextCompat.getSystemService(this, PowerManager::class.java)?.isInteractive != false

    private fun enabledKeyboardPackages(): Set<String> = runCatching {
        ContextCompat.getSystemService(this, InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }?.toSet()
    }.getOrNull().orEmpty()

    private fun logFailureRateLimited(key: String, message: String, t: Throwable?) {
        val now = SystemClock.elapsedRealtime()
        val last = lastFailureLogAt[key]
        if (last != null && now - last < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt[key] = now
        GuardianLog.w(applicationContext, message, t)
    }

    private fun screenshotErrorName(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal error"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "interval too short"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "secure window (screenshot-protected content)"
        else -> "code $code"
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    companion object {
        private val instanceCounter = AtomicInteger()

        /** Recycles a node on Android < 13 (a no-op / deprecated from 13 on). */
        @Suppress("DEPRECATION")
        private fun releaseNode(node: AccessibilityNodeInfo) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) runCatching { node.recycle() }
        }

        /** Repeated identical failures are logged at most this often. */
        private const val FAILURE_LOG_INTERVAL_MS = 60_000L

        /** Whether the user has turned the service on in Settings → Accessibility. */
        fun isEnabledInSettings(context: Context): Boolean {
            val component = ComponentName(context, GuardianAccessibilityService::class.java)
            val manager = ContextCompat.getSystemService(context, AccessibilityManager::class.java)
            val viaManager = manager?.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.any { info ->
                info.resolveInfo?.serviceInfo?.let {
                    it.packageName == component.packageName && it.name == component.className
                } == true
            } == true
            if (viaManager) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == component
            }
        }

        /** Intent that opens the system Accessibility settings screen. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Live scanning state for the status screen. Written by the scanner, read by the UI;
 * values are informational only.
 */
object ScanStatus {
    @Volatile var connected = false
    @Volatile var unsupported = false
    @Volatile var modelLoaded = false
    @Volatile var fastModePackage: String? = null
    @Volatile var framesScanned = 0L
        private set
    @Volatile var lastFrameAtMs = 0L
        private set
    @Volatile var lastScore = 0f
        private set
    @Volatile var lastSource: TriggerSource? = null
        private set
    @Volatile var confirmedCount = 0
    @Volatile var suppressedCount = 0

    // Stage 4: text scanning
    @Volatile var textEntries = 0
    @Volatile var textFailed = false
    @Volatile var textChecks = 0L
        private set
    @Volatile var lastTextCheckAtMs = 0L
        private set
    @Volatile var textDetectionCount = 0
    @Volatile var textSuppressedCount = 0

    fun onTextCheck(atMs: Long) {
        textChecks++
        lastTextCheckAtMs = atMs
    }

    fun onFrame(atMs: Long, score: Float, source: TriggerSource) {
        framesScanned++
        lastFrameAtMs = atMs
        lastScore = score
        lastSource = source
    }
}
