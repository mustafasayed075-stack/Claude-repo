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
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
 * Detection and logging only — no lock/block action in this stage.
 *
 * `takeScreenshot()` needs Android 11 (API 30); on older versions the service logs a
 * warning and stays idle. All scanning state lives on one worker thread; the
 * accessibility callbacks (main thread) only post to it.
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ScanStatus.unsupported = true
            GuardianLog.w(
                this,
                "Screen scanning unsupported on this Android version (API ${Build.VERSION.SDK_INT}); " +
                    "requires Android 11 (API 30)+. Accessibility service stays idle."
            )
            return
        }

        scheduler.overlayPackages = ScanConfig.OVERLAY_PACKAGES + enabledKeyboardPackages()
        val initialForeground = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

        val thread = HandlerThread("guardian-screen-scan").also { it.start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        worker = handler
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
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        worker?.post { onForegroundChanged(pkg) }
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
            val score = model.classify(frame)
            ScanStatus.onFrame(System.currentTimeMillis(), score, source)
            val confirmation = confirmer.onFrame(score, SystemClock.elapsedRealtime(), source)
            if (ScanConfig.LOG_EVERY_FRAME_SCORE) {
                // TEMPORARY calibration logging (see ScanConfig.LOG_EVERY_FRAME_SCORE).
                val positives = if (confirmation != null) confirmer.requiredPositives else confirmer.pendingPositives
                GuardianLog.i(
                    applicationContext,
                    ScanLog.frameLine(
                        score, confirmer.threshold, source, scheduler.foregroundPackage,
                        positives, confirmer.requiredPositives
                    )
                )
            }
            if (confirmation != null) onConfirmed(confirmation, frame)
        } finally {
            frame.recycle()
        }
    }

    private fun onConfirmed(confirmation: DetectionConfirmer.Confirmation, frame: Bitmap) {
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
            thumbnailFile = thumbnail
        )
        runCatching { DetectionStore.appendMetadata(ctx, event) }
            .onFailure { GuardianLog.e(ctx, "Screen scan: failed to write detection metadata.", it) }
        ScanStatus.confirmedCount++

        val scores = confirmation.frames.joinToString { "%.3f".format(it.score) }
        GuardianLog.w(
            ctx,
            "CONFIRMED screen detection: confidence=${"%.3f".format(latest.score)} trigger=${latest.source.label} " +
                "app=${event.foregroundPackage ?: "unknown"} frames=[$scores] thumbnail=${thumbnail ?: "not saved"} " +
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
        ScanStatus.modelLoaded = false
        ScanStatus.fastModePackage = null
        val handler = worker ?: return
        worker = null
        handler.removeCallbacksAndMessages(null)
        handler.post {
            classifier?.close()
            classifier = null
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

    fun onFrame(atMs: Long, score: Float, source: TriggerSource) {
        framesScanned++
        lastFrameAtMs = atMs
        lastScore = score
        lastSource = source
    }
}
