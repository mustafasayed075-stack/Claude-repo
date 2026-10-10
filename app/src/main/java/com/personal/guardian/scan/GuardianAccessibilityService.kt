package com.personal.guardian.scan

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.personal.guardian.lock.DevicePolicyLock
import com.personal.guardian.lock.LockController
import com.personal.guardian.reflection.ReflectionLauncher
import com.personal.guardian.reflection.ReflectionSettings
import com.personal.guardian.lock.LockLog
import com.personal.guardian.lock.LockOutcome
import com.personal.guardian.text.KeywordList
import com.personal.guardian.text.KeywordMatcher
import com.personal.guardian.text.KeywordTier
import com.personal.guardian.text.TextExtractor
import com.personal.guardian.text.TextFingerprint
import com.personal.guardian.text.TextNode
import com.personal.guardian.text.TextScanTrigger
import com.personal.guardian.text.TextWindows
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
 *  - switches to [ScanConfig.FAST_INTERVAL_MS] as soon as a Fast Scan App with
 *    image scanning on ([FastScanList.imagePackages], edited by the user in
 *    [FastScanSettings]) comes to the foreground, and back to the baseline when it
 *    leaves (event trigger). The baseline capture runs in every app;
 *  - classifies each frame on-device ([NsfwClassifier]) twice over: the whole
 *    downscaled screen, and — region scanning — up to
 *    [ScanConfig.REGION_MAX_PER_CAPTURE] image-bearing elements from the active
 *    window's node tree (image/video/sticker views, [ImageRegionFinder]) cropped out
 *    of the same screenshot at their own resolution, so a sticker or a small player
 *    isn't squashed to a few pixels; the frame counts as positive if either path
 *    does ([FrameVerdict]), and that score feeds [DetectionConfirmer];
 *  - confirmed detections are saved locally
 *    ([DetectionStore]), logged ([GuardianLog]), shown as a notification
 *    ([DetectionNotifier]) and published on [DetectionBus] for later stages.
 *
 * Stage 4 adds a second, independent path in the same service: on window-content
 * (and window-state) changes in a Fast Scan App with text scanning on
 * ([FastScanList.textPackages]), the visible text of the active
 * window's node tree is read (no OCR, no screenshot) and checked on-device by
 * [KeywordMatcher]; matches go through the same pipeline — cooldown, review log
 * (text snippet instead of a thumbnail), GuardianLog, notification, [DetectionBus].
 *
 * Stage 5 locks the device on confirmed detections ([LockController], fed from
 * [DetectionBus]): image and explicit-tier text detections lock at once; a
 * borderline-only text detection first gets fast image checks of the screen for
 * [ScanConfig.CORROBORATION_WINDOW_MS] and locks only if one is positive. Needs an
 * active device admin; without one, locking is skipped with a warning.
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
    // Suggestive tier: two suggestive frames within the window lock, independent of the
    // same-content cooldown and of the explicit confirmer above (Stage 5).
    private val suggestiveConfirmer = SuggestiveConfirmer()
    private val cooldown = DetectionCooldown()
    private val fingerprintPixels = IntArray(ScreenFingerprint.WIDTH * ScreenFingerprint.HEIGHT)
    // Region scanning (worker thread).
    private val regionCache = RegionScoreCache()
    private val regionScorer = RegionScorer(regionCache) { SystemClock.elapsedRealtime() }
    private val appSwitch = AppSwitchTracker()
    // Blind-spot escalation: shrinking grace each time Guardian can't read the screen.
    private val blindSpot = BlindSpotPolicy({ SystemClock.elapsedRealtime() })
    // Consecutive unreadable captures (secure/blank), for the black-frame guardrail.
    private var consecutiveUnreadable = 0
    private val contentPixels = IntArray(RegionContent.GRID * RegionContent.GRID)
    // Reused scratch for the whole-screen letterbox crop (Screen scanning → letterbox crop).
    private val letterboxPixels = IntArray(RegionContent.GRID * RegionContent.GRID)
    private val letterboxLuma = IntArray(RegionContent.GRID * RegionContent.GRID)
    private val costStats = ScanCostStats()
    private var classifier: NsfwClassifier? = null
    private var captureInFlight = false
    private val lastFailureLogAt = HashMap<String, Long>()

    private val tick = Runnable { onTick() }

    // Stage 4: text scanning (worker-thread state, except the thread-safe trigger).
    private val textTrigger = TextScanTrigger { fastScanApps.textPackages }

    /** The user's Fast Scan Apps list; replaced (from any thread) when it is edited. */
    @Volatile
    private var fastScanApps: FastScanList = FastScanList.EMPTY
    private val fastScanListener = FastScanSettings.Listener { list ->
        fastScanApps = list
        worker?.post { onFastScanAppsChanged(list) }
    }
    private val textCooldown = DetectionCooldown(cooldownMs = ScanConfig.TEXT_COOLDOWN_MS, maxDistance = 0)
    private var keywordMatcher: KeywordMatcher? = null
    private val textCheck = Runnable { runTextCheck() }

    // Stage 5: lock (worker-thread state).
    private var lockController: LockController? = null
    private val lockTimer = Runnable { handleLock(lockController?.onLockTimer()) }
    private val corroborationDeadline = Runnable { handleLock(lockController?.onCorroborationDeadline()) }
    private val lockListener = DetectionListener { event -> handleLock(lockController?.onDetection(event)) }
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Delivered on the worker thread (registered with its handler).
            if (intent.action == Intent.ACTION_USER_PRESENT) handleLock(lockController?.onUserPresent())
        }
    }
    private var userPresentRegistered = false

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

        fastScanApps = FastScanSettings.get(this)
        FastScanSettings.addListener(fastScanListener)
        scheduler.updateWatchedPackages(fastScanApps.imagePackages)

        val thread = HandlerThread("guardian-screen-scan").also { it.start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        worker = handler

        // Stage 4: text scanning works on every supported Android version.
        handler.post { loadKeywordList() }
        // Stage 5: lock on confirmed detections (text locking works on every version too).
        if (ScanConfig.LOCK_ENABLED) startLock(handler)

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
                    "fast every ${scheduler.fastIntervalMs} ms for ${scheduler.watchedPackages.size} Fast Scan Apps with image on " +
                    "(thresholds: screen ${ScanConfig.NSFW_THRESHOLD}, region ${ScanConfig.REGION_THRESHOLD}; confirm ${ScanConfig.CONFIRMATION_COUNT} " +
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
        // Stage 4: content/state change or a text-field edit in a text-scanned app →
        // one debounced text check. A text-changed event carries the field's live text.
        val fieldText = if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && !event.isPassword) {
            event.text.joinToString(" ")
        } else null
        val decision = textTrigger.onEvent(type, pkg, fieldText)
        val handler = worker
        if (decision == TextScanTrigger.Decision.SCHEDULED &&
            (handler == null || !handler.postDelayed(textCheck, ScanConfig.TEXT_CHECK_DEBOUNCE_MS))
        ) {
            textTrigger.onCheckStarted()
        }
        if (ScanConfig.LOG_TEXT_FIELD_EVENTS && decision != TextScanTrigger.Decision.IGNORED &&
            TextScanTrigger.isFieldEvent(type, event.className)
        ) {
            // TEMPORARY diagnostics (see ScanConfig.LOG_TEXT_FIELD_EVENTS); the text itself is not logged.
            val what = if (decision == TextScanTrigger.Decision.SCHEDULED) {
                "check scheduled in ${ScanConfig.TEXT_CHECK_DEBOUNCE_MS} ms"
            } else "debounced into the pending check"
            val line = "Text trigger: $pkg ${AccessibilityEvent.eventTypeToString(type)} " +
                "(${event.className?.toString()?.substringAfterLast('.') ?: "?"}" +
                (fieldText?.let { ", field ${it.length} chars" } ?: "") + ") → $what."
            handler?.post { GuardianLog.i(applicationContext, line) }
        }
        // A text-field edit in an app that is NOT in the text-scan list: say so (rate-limited
        // per app), so "typed in app X but nothing happened" is explained, not silent.
        if (ScanConfig.LOG_TEXT_FIELD_EVENTS && decision == TextScanTrigger.Decision.IGNORED &&
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && pkg != null
        ) {
            handler?.post {
                logFailureRateLimited(
                    "text-unwatched-$pkg",
                    "Text trigger ignored: $pkg typed in a text field but it is not in the text-scan list.",
                    null
                )
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
        // Positives must not combine across apps: a positive frame in the previous app
        // plus one in the new app's first frames used to confirm a detection there.
        if (appSwitch.onWindowStateChanged(pkg, SystemClock.elapsedRealtime(), scheduler.overlayPackages)) {
            confirmer.reset()
            suggestiveConfirmer.reset()
        }
        if (classifier == null) return
        if (!scheduler.onForegroundChanged(pkg)) return
        onFastModeChanged("$pkg in foreground")
    }

    /** The user edited the Fast Scan Apps list: the app in front may gain or lose fast capture. */
    private fun onFastScanAppsChanged(list: FastScanList) {
        if (!scheduler.updateWatchedPackages(list.imagePackages) || classifier == null) return
        onFastModeChanged("Fast Scan Apps edited, ${scheduler.foregroundPackage} in foreground")
    }

    private fun onFastModeChanged(reason: String) {
        val handler = worker ?: return
        val now = SystemClock.elapsedRealtime()
        if (scheduler.isFastMode) {
            ScanStatus.fastModePackage = scheduler.foregroundPackage
            GuardianLog.i(applicationContext, "Screen scan: fast capture ON ($reason, every ${scheduler.fastIntervalMs} ms).")
        } else {
            ScanStatus.fastModePackage = null
            GuardianLog.i(applicationContext, "Screen scan: fast capture OFF ($reason), back to every ${scheduler.baselineIntervalMs} ms.")
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
            suggestiveConfirmer.reset()
            scheduler.onCaptured(now) // keep the cadence without spinning
            return
        }

        val source = scheduler.sourceAt(now)
        scheduler.onCaptured(now)
        // Region pass: read the node tree before the screenshot too (see RegionStability);
        // not while a window transition is still settling.
        val regionsBefore = if (ScanConfig.REGION_SCAN_ENABLED && appSwitch.isSettled(now)) readRegions() else null
        captureInFlight = true
        val onWorker = Executor { handler.post(it) }
        takeScreenshot(Display.DEFAULT_DISPLAY, onWorker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    // Readability (and the blind-spot reset) is decided inside, once we know
                    // whether the frame is blank/near-black.
                    processScreenshot(result, source, regionsBefore)
                } catch (t: Throwable) {
                    logFailureRateLimited("processing", "Screen scan: frame processing failed.", t)
                } finally {
                    captureInFlight = false
                }
            }

            override fun onFailure(errorCode: Int) {
                captureInFlight = false
                val pkg = scheduler.foregroundPackage
                logFailureRateLimited(
                    "screenshot-$errorCode",
                    ScanLog.captureIssueLine(
                        "FAILED (${screenshotErrorName(errorCode)})", pkg, fastScanLabel(pkg)
                    ),
                    null
                )
                // A secure / screenshot-protected window: Guardian can't read the screen.
                if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) onCaptureUnreadable(pkg)
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processScreenshot(result: ScreenshotResult, source: TriggerSource, regionsBefore: RegionSnapshot?) {
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
            // Path 1: the whole (downscaled) screen. In a letterboxed media viewer (a big
            // image on black bands with UI chrome around it) the bands and chrome dilute
            // the signal, so classify the cropped-out image when one is found; otherwise
            // the full frame, unchanged (Screen scanning → letterbox crop). The explicit region
            // path below is untouched.
            val t0 = SystemClock.elapsedRealtime()
            val crop = letterboxCrop(frame)
            val whole = try {
                model.classify(crop ?: frame)
            } finally {
                if (crop != null && crop !== frame) crop.recycle()
            }
            val t1 = SystemClock.elapsedRealtime()
            // Path 2: image-bearing elements cropped out of the same frame.
            val regions = if (regionsBefore != null) scoreRegions(model, frame, regionsBefore) else RegionScorer.Pass.NONE
            val t2 = SystemClock.elapsedRealtime()
            // Region time includes the node-tree read made before the screenshot.
            costStats.record(
                t1 - t0, t2 - t1 + (regionsBefore?.readMs ?: 0), regions.found, regions.classified, regions.cacheHits, regions.budgetSkips, regions.blankSkips
            )
                ?.let { GuardianLog.i(applicationContext, it, diagnostic = true) }

            val verdict = FrameVerdict.combine(whole, regions.scores)
            ScanStatus.onFrame(System.currentTimeMillis(), verdict.score, source)
            val now = SystemClock.elapsedRealtime()
            val pkg = scheduler.foregroundPackage
            // Readable vs blank/near-black (a black media-viewer background, a secure
            // render): drives the blind-spot path and its black-frame guardrail.
            val readable = regionContent(frame) == RegionContent.Verdict.OK
            if (readable) onCaptureReadable() else onCaptureUnreadable(pkg)

            val confirmation = confirmer.onFrame(verdict.score, now, source, verdict.positive)
            // Additive per-class layer: raw-class rules on top of the combined paths.
            val suggestiveThreshold = ScanSensitivitySettings.suggestiveThreshold(applicationContext)
            val perClass = PerClassLayer.evaluate(whole, regions.scores.map { it.scores }, suggestiveThreshold)
            // Suggestive tier: judged against the sensitivity threshold and fed every frame,
            // independent of the same-content cooldown. Two suggestive frames within the
            // window lock. The combined-signal suggestive (an explicit frame is already on
            // the immediate path, so excluded) OR a per-class sexy/region-hentai hit.
            val suggestive = (!verdict.positive && verdict.score >= suggestiveThreshold) || perClass.suggestive
            val suggestiveConfirmed = suggestiveConfirmer.onFrame(now, suggestive)
            if (ScanConfig.LOG_EVERY_FRAME_SCORE) {
                // TEMPORARY calibration logging (see ScanConfig.LOG_EVERY_FRAME_SCORE).
                val positives = if (confirmation != null) confirmer.requiredPositives else confirmer.pendingPositives
                val level = NsfwLevel.of(verdict.score, suggestiveThreshold, ScanConfig.REGION_THRESHOLD)
                GuardianLog.i(
                    applicationContext,
                    ScanLog.frameLine(
                        verdict.scores,
                        if (verdict.region != null) ScanConfig.REGION_THRESHOLD else confirmer.threshold,
                        source, pkg, positives, confirmer.requiredPositives,
                        if (ScanConfig.REGION_SCAN_ENABLED) ScanLog.regionSummary(regions.scores) else null,
                        signal = verdict.score,
                        level = level.label,
                        fastScan = fastScanLabel(pkg)
                    )
                )
                if (!readable) {
                    GuardianLog.i(applicationContext, ScanLog.captureIssueLine("BLANK frame", pkg, fastScanLabel(pkg)))
                }
                // Per-class "suspect" frames are logged (not locked) so the thresholds can
                // be tuned from the log.
                if (perClass.suspect && !perClass.pornImmediate && !suggestive) {
                    GuardianLog.i(applicationContext, ScanLog.perClassLine(whole, regions.scores, pkg))
                }
            }
            if (confirmation != null) onConfirmed(confirmation, frame, verdict)
            // Per-class porn: an immediate (one-frame) lock, after the explicit confirmation.
            if (perClass.pornImmediate) onPerClassImmediate(verdict, "porn")
            // Suggestive tier: locks on its own, after the explicit confirmation above
            // (so a frame that also confirmed explicitly locks as that first).
            else if (suggestiveConfirmed) onSuggestiveConfirmed(verdict)
            // Stage 5: a borderline text match waiting for an image check (after the
            // confirmation, so a confirmed image detection locks as "image" first).
            lockController?.let { lc ->
                if (lc.isCorroborating()) handleLock(lc.onCorroborationFrame(verdict.positive, verdict.score))
            }
        } finally {
            frame.recycle()
        }
    }

    /** Image-bearing elements of the active window at one instant, and its package. */
    private class RegionSnapshot(val packageName: String?, val regions: List<ImageRegion>, val readMs: Long)

    /** Reads the active window's image-bearing elements ([ImageRegionFinder]); null if there is no window. */
    private fun readRegions(): RegionSnapshot? {
        val start = SystemClock.elapsedRealtime()
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        return try {
            val regions = ImageRegionFinder.collect(
                AccessibilityRegionNode(root), displayBox(), ImageRegionFinder.Limits(minRegionSidePx())
            )
            RegionSnapshot(root.packageName?.toString(), regions, SystemClock.elapsedRealtime() - start)
        } catch (t: Throwable) {
            logFailureRateLimited("regions", "Screen scan: region lookup failed.", t)
            null
        } finally {
            releaseNode(root)
        }
    }

    /**
     * Scores the image-bearing elements of [frame] ([RegionScorer]: at most
     * [ScanConfig.REGION_MAX_PER_CAPTURE], within [ScanConfig.REGION_TIME_BUDGET_MS],
     * unchanged content from the cache, blank crops skipped). Only elements whose
     * bounds are the same in the node tree read before the screenshot ([before]) and
     * after it, in the same app, are used ([RegionStability]); nothing is used if a
     * window change happened meanwhile, or if the screenshot's size doesn't match the
     * display the bounds refer to.
     */
    private fun scoreRegions(model: NsfwClassifier, frame: Bitmap, before: RegionSnapshot): RegionScorer.Pass {
        val display = displayBox()
        if (display.width != frame.width || display.height != frame.height) {
            logFailureRateLimited(
                "region-size",
                "Screen scan: screenshot ${frame.width}x${frame.height} differs from display ${display.width}x${display.height}; " +
                    "region pass skipped (bounds wouldn't line up).",
                null
            )
            return RegionScorer.Pass.NONE
        }
        val after = readRegions() ?: return RegionScorer.Pass.NONE
        if (!appSwitch.isSettled(SystemClock.elapsedRealtime())) return RegionScorer.Pass.NONE
        val regions = RegionStability.stable(before.packageName, before.regions, after.packageName, after.regions)
        if (regions.isEmpty()) return RegionScorer.Pass.NONE
        val pass = regionScorer.score(
            regions, frame.width, frame.height, minRegionSidePx(),
            crop = { c -> Bitmap.createBitmap(frame, c.left, c.top, c.width, c.height) },
            fingerprint = { fingerprint(it) },
            classify = { model.classify(it) },
            release = { if (it !== frame) it.recycle() },
            content = { regionContent(it) }
        )
        ScanStatus.regionsClassified += pass.classified
        ScanStatus.regionCacheHits += pass.cacheHits
        return pass
    }

    private fun minRegionSidePx(): Int = (ScanConfig.REGION_MIN_SIDE_DP * resources.displayMetrics.density).toInt()

    /** The default display's full size in its current rotation — the pixel space of node bounds and screenshots. */
    @Suppress("DEPRECATION")
    private fun displayBox(): Box {
        val metrics = android.util.DisplayMetrics()
        ContextCompat.getSystemService(this, android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        return Box(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Screen scanning → letterbox crop. If [frame] is a letterboxed media-viewer screen (a large
     * image on black bands, with a status bar / header / thumbnail strip around it), this
     * returns that image cropped out, so the black bands and chrome don't dilute the
     * whole-screen signal. Returns null when there's nothing worth cropping (no bands, or
     * the crop would be trivial) — then the caller classifies the whole frame unchanged,
     * so apps without black bars are not affected. The returned bitmap is a new bitmap the
     * caller recycles. Geometry is in [LetterboxCrop]; here we only build the luma grid
     * (reusing [RegionContent.GRID] and the same Rec. 601 luma as [ImageRegions]) and map
     * the result rect back to frame pixels.
     */
    private fun letterboxCrop(frame: Bitmap): Bitmap? {
        val grid = RegionContent.GRID
        var current = frame
        for ((w, h) in NsfwPreprocessor.downscaleSteps(frame.width, frame.height, grid)) {
            val next = Bitmap.createScaledBitmap(current, w, h, true)
            if (current !== frame && current !== next) current.recycle()
            current = next
        }
        try {
            current.getPixels(letterboxPixels, 0, grid, 0, 0, grid, grid)
        } finally {
            if (current !== frame) current.recycle()
        }
        for (i in letterboxPixels.indices) {
            val p = letterboxPixels[i]
            letterboxLuma[i] = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val rect = LetterboxCrop.contentRect(letterboxLuma, grid, grid) ?: return null
        // Grid cells → frame pixels; clamp to the frame and keep at least 1px each way.
        val left = (rect.left.toLong() * frame.width / grid).toInt().coerceIn(0, frame.width - 1)
        val top = (rect.top.toLong() * frame.height / grid).toInt().coerceIn(0, frame.height - 1)
        val right = ((rect.left + rect.width).toLong() * frame.width / grid).toInt().coerceIn(left + 1, frame.width)
        val bottom = ((rect.top + rect.height).toLong() * frame.height / grid).toInt().coerceIn(top + 1, frame.height)
        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }

    /** [RegionContent] verdict for a crop, on an area-averaged [RegionContent.GRID]² downsample. */
    private fun regionContent(crop: Bitmap): RegionContent.Verdict {
        var current = crop
        for ((w, h) in NsfwPreprocessor.downscaleSteps(crop.width, crop.height, RegionContent.GRID)) {
            val next = Bitmap.createScaledBitmap(current, w, h, true)
            if (current !== crop && current !== next) current.recycle()
            current = next
        }
        try {
            current.getPixels(contentPixels, 0, RegionContent.GRID, 0, 0, RegionContent.GRID, RegionContent.GRID)
        } finally {
            if (current !== crop) current.recycle()
        }
        return RegionContent.assess(contentPixels)
    }

    private fun onConfirmed(confirmation: DetectionConfirmer.Confirmation, frame: Bitmap, verdict: FrameVerdict.Result) {
        val ctx = applicationContext
        val region = verdict.region
        val scores = verdict.scores
        // Same-content check on what scored: the region's own fingerprint (so scrolling a
        // chat doesn't re-report the same sticker), or the whole screen's.
        if (!cooldown.shouldReport(SystemClock.elapsedRealtime(), region?.fingerprint ?: fingerprint(frame))) {
            // Same content as a detection reported within the cooldown: no notification or
            // log entry, but it still locks (ScanConfig.LOCK_ON_SUPPRESSED_REPEATS).
            ScanStatus.suppressedCount++
            if (ScanConfig.LOCK_ON_SUPPRESSED_REPEATS) {
                handleLock(
                    lockController?.onDetection(
                        DetectionEvent(
                            System.currentTimeMillis(), confirmation.latest.score, confirmation.latest.source,
                            scheduler.foregroundPackage, null, scores, region = region?.region?.label
                        )
                    )
                )
            }
            return
        }
        val now = System.currentTimeMillis()
        val latest = confirmation.latest
        // The review thumbnail shows what scored: the region itself, else the screen.
        val thumbnail = if (region == null) DetectionStore.saveThumbnail(ctx, frame, now) else {
            val c = region.crop
            val crop = Bitmap.createBitmap(frame, c.left, c.top, c.width, c.height)
            try {
                DetectionStore.saveThumbnail(ctx, crop, now)
            } finally {
                if (crop !== frame) crop.recycle()
            }
        }
        val event = DetectionEvent(
            timestampMs = now,
            confidence = latest.score,
            source = latest.source,
            foregroundPackage = scheduler.foregroundPackage,
            thumbnailFile = thumbnail,
            classScores = scores,
            region = region?.region?.label
        )
        runCatching { DetectionStore.appendMetadata(ctx, event) }
            .onFailure { GuardianLog.e(ctx, "Screen scan: failed to write detection metadata.", it) }
        ScanStatus.confirmedCount++

        val frameSignals = confirmation.frames.joinToString { "%.3f".format(it.score) }
        GuardianLog.w(
            ctx,
            "CONFIRMED screen detection: signal=${"%.3f".format(latest.score)} (${scores.breakdown()}) trigger=${latest.source.label} " +
                "app=${event.foregroundPackage ?: "unknown"} scored=${region?.region?.label ?: "whole screen"} " +
                "frames=[$frameSignals] thumbnail=${thumbnail ?: "not saved"} " +
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

    /**
     * Two suggestive frames fell within [ScanConfig.SUGGESTIVE_WINDOW_MS]. Locks through
     * the same controller as an explicit detection, bypassing the same-content cooldown
     * (the suggestive counter is deliberately independent of it, so the same image across
     * two frames still acts). No notification/metadata beyond the lock and this log line,
     * matching the blind-spot lock path.
     */
    private fun onSuggestiveConfirmed(verdict: FrameVerdict.Result) {
        val ctx = applicationContext
        val pkg = scheduler.foregroundPackage
        GuardianLog.w(
            ctx,
            "SUGGESTIVE lock: ${ScanConfig.SUGGESTIVE_COUNT} suggestive frames within ${ScanConfig.SUGGESTIVE_WINDOW_MS} ms " +
                "(signal=${"%.3f".format(verdict.score)}, ${verdict.scores.breakdown()}) " +
                "scored=${verdict.region?.region?.label ?: "whole screen"} app=${pkg ?: "unknown"}."
        )
        ScanStatus.confirmedCount++
        handleLock(
            lockController?.onDetection(
                DetectionEvent(
                    System.currentTimeMillis(), verdict.score, TriggerSource.EVENT,
                    pkg, null, verdict.scores, region = verdict.region?.region?.label
                )
            )
        )
    }

    /**
     * A per-class rule that locks immediately (one frame) — currently porn at or above
     * [ScanConfig.PORN_IMMEDIATE_THRESHOLD]. Locks through the same controller as a
     * detection, bypassing the confirmer and cooldown. Log + lock only.
     */
    private fun onPerClassImmediate(verdict: FrameVerdict.Result, cls: String) {
        val ctx = applicationContext
        val pkg = scheduler.foregroundPackage
        GuardianLog.w(
            ctx,
            "PER-CLASS lock (immediate): $cls (${verdict.scores.breakdown()}) " +
                "scored=${verdict.region?.region?.label ?: "whole screen"} app=${pkg ?: "unknown"}."
        )
        ScanStatus.confirmedCount++
        handleLock(
            lockController?.onDetection(
                DetectionEvent(
                    System.currentTimeMillis(), verdict.scores.porn, TriggerSource.EVENT,
                    pkg, null, verdict.scores, region = verdict.region?.region?.label
                )
            )
        )
    }

    // ---- Stage 5: lock ----

    private fun startLock(handler: Handler) {
        val ctx = applicationContext
        handler.post {
            lockController = LockController(
                DevicePolicyLock(ctx),
                LockLog { level, message ->
                    when (level) {
                        LockLog.Level.INFO -> GuardianLog.i(ctx, message)
                        LockLog.Level.WARN -> GuardianLog.w(ctx, message)
                        LockLog.Level.ERROR -> GuardianLog.e(ctx, message)
                    }
                },
                SystemClock::elapsedRealtime,
                // Reflection Mode duration, read at each lock (30 s minimum enforced in code).
                { ReflectionSettings.durationMs(ctx) }
            )
            val admin = DevicePolicyLock(ctx).isAdminActive()
            GuardianLog.i(
                ctx,
                "Lock armed: Reflection Mode duration ${ReflectionSettings.durationSeconds(ctx)} s " +
                    "(${ReflectionSettings.library(ctx).size} reminder(s)), borderline text corroboration window " +
                    "${ScanConfig.CORROBORATION_WINDOW_MS} ms; device admin active: ${yesNo(admin)}" +
                    if (admin) "." else " — locks will be skipped until \"Activate device admin\" is pressed."
            )
        }
        DetectionBus.register(lockListener)
        // Unlock attempts during a lock. USER_PRESENT can't be declared in the manifest
        // on Android 8+, so it is registered here, delivered on the worker thread.
        ContextCompat.registerReceiver(
            this, userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), null, handler,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        userPresentRegistered = true
    }

    /** Acts on what the lock controller decided (worker thread). */
    /** Fast-scan membership of [pkg] for the log: "text+image", "text", "image", "off" or "no". */
    private fun fastScanLabel(pkg: String?): String {
        if (pkg == null) return "unknown"
        val app = FastScanSettings.get(this)[pkg] ?: return "no"
        return when {
            app.text && app.image -> "text+image"
            app.text -> "text"
            app.image -> "image"
            else -> "off"
        }
    }

    /** A readable capture: clears the black-frame guardrail and drives the 1-hour reset. */
    private fun onCaptureReadable() {
        consecutiveUnreadable = 0
        onReadableFrame()
    }

    /**
     * An unreadable capture — a secure/screenshot-protected window, or a blank/near-black
     * frame. Only acts when the foreground app is on the blind-spot list, and only after
     * [ScanConfig.BLIND_SPOT_CONFIRM_FRAMES] *consecutive* unreadable captures, so a single
     * transient blank (a media viewer loading a frame) is ignored. Worker thread.
     */
    private fun onCaptureUnreadable(pkg: String?) {
        consecutiveUnreadable++
        if (pkg == null || !BlindSpotSettings.get(this).contains(pkg)) return
        if (consecutiveUnreadable >= ScanConfig.BLIND_SPOT_CONFIRM_FRAMES) onBlindFrame()
    }

    /** A frame Guardian could read — drives the blind-spot escalation reset (worker thread). */
    private fun onReadableFrame() {
        if (blindSpot.onVisible() is BlindSpotPolicy.Outcome.LevelReset) {
            GuardianLog.i(applicationContext, "Blind-spot: an hour of readable screen — grace reset to ${ScanConfig.BLIND_SPOT_GRACE_MS[0] / 1000}s.")
        }
    }

    /**
     * A blind-spot-listed app stayed unreadable. Starts or continues the escalating grace;
     * locks (via the same path as a detection) once the grace for this entry has elapsed.
     * Worker thread.
     */
    private fun onBlindFrame() {
        when (val o = blindSpot.onBlind()) {
            is BlindSpotPolicy.Outcome.GraceStarted -> GuardianLog.w(
                applicationContext,
                "Blind-spot: can't read the screen (entry #${o.episode}); grace ${o.graceMs / 1000}s before locking."
            )
            is BlindSpotPolicy.Outcome.Act -> {
                GuardianLog.w(
                    applicationContext,
                    "Blind-spot lock: grace elapsed while the screen stayed unreadable (entry #${o.episode})."
                )
                handleLock(
                    lockController?.onDetection(
                        DetectionEvent(
                            System.currentTimeMillis(), 1f, TriggerSource.EVENT,
                            scheduler.foregroundPackage, null
                        )
                    )
                )
            }
            else -> Unit
        }
    }

    private fun handleLock(outcome: LockOutcome?) {
        val handler = worker ?: return
        when (outcome) {
            is LockOutcome.Locked -> {
                ScanStatus.onLock(System.currentTimeMillis(), outcome.source.label)
                // The lock settles any corroboration: back to the normal capture rate.
                scheduler.corroborateUntil(Long.MIN_VALUE)
                handler.removeCallbacks(corroborationDeadline)
                handler.removeCallbacks(lockTimer)
                handler.postDelayed(lockTimer, outcome.untilMs - SystemClock.elapsedRealtime())
                // Stage 6: show Reflection Mode in addition to lockNow() (belt and suspenders).
                ReflectionLauncher.launch(applicationContext, outcome.source, outcome.detectionId, outcome.durationMs)
            }
            is LockOutcome.Relocked -> ScanStatus.relockCount++
            is LockOutcome.Skipped -> ScanStatus.lockSkippedCount++
            is LockOutcome.CorroborationStarted -> startCorroborationChecks(handler, outcome.deadlineMs)
            is LockOutcome.LockEnded -> onLockEnded()
            else -> Unit
        }
    }

    /**
     * A lock period finished. Re-arm so that if the same suspect content is still on
     * screen it is evaluated afresh and can lock again: clear the confirmers (their
     * positives were consumed by the lock), drop the region cache and the same-content
     * cooldown memory (so repeat-suppression can't block the next lock), and take a
     * capture right away instead of waiting for the next tick. Does not change any
     * threshold or the confirmation rules. Worker thread.
     */
    private fun onLockEnded() {
        val handler = worker ?: return
        confirmer.reset()
        suggestiveConfirmer.reset()
        regionCache.clear()
        cooldown.clear()
        GuardianLog.i(applicationContext, "Lock ended: re-armed; still-visible suspect content will be re-evaluated.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handler.removeCallbacks(tick)
            handler.post(tick)
        }
    }

    /** Image checks of the screen, as fast as the platform allows, until [deadlineMs]. */
    private fun startCorroborationChecks(handler: Handler, deadlineMs: Long) {
        val now = SystemClock.elapsedRealtime()
        handler.removeCallbacks(corroborationDeadline)
        handler.postDelayed(corroborationDeadline, (deadlineMs - now).coerceAtLeast(0))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || classifier == null) {
            GuardianLog.w(
                applicationContext,
                "Borderline text match: no image check possible (screen capture needs Android 11+ and the model); " +
                    "the corroboration window will expire without a lock."
            )
            return
        }
        scheduler.corroborateUntil(deadlineMs)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, scheduler.delayForImmediateCapture(now))
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
                        "(${ScanConfig.KEYWORD_ASSET}); Fast Scan Apps with text on " +
                        "(${fastScanApps.textPackages.size} now), on content changes."
                )
            }
        } catch (t: Throwable) {
            GuardianLog.e(applicationContext, "Text scanning: failed to load keyword list; text scanning disabled.", t)
            ScanStatus.textFailed = true
            null
        }
    }

    private fun runTextCheck() {
        val pending = textTrigger.onCheckStarted() ?: return
        val matcher = keywordMatcher ?: return
        val pkg = pending.packageName
        // The app may have had text scanning switched off within the debounce.
        if (!textTrigger.isWatched(pkg)) {
            if (ScanConfig.LOG_TEXT_FIELD_EVENTS) GuardianLog.i(
                applicationContext,
                "Text check for $pkg skipped: app is not in the text-scan list."
            )
            return
        }
        // Per-check diagnostics (no raw text): package, windows, nodes, chars, result.
        val diagnose = ScanConfig.LOG_TEXT_FIELD_EVENTS
        val windows = textWindows(pkg)
        try {
            if (windows.roots.isEmpty()) {
                // The app left the screen within the debounce: nothing of it to check.
                if (diagnose) GuardianLog.i(
                    applicationContext,
                    "Text check fired for $pkg (${pending.events} events, ${pending.fieldEvents} from a text field): " +
                        "skipped, no window of the app on screen (active window: ${windows.activePackage ?: "none"})."
                )
                return
            }
            // The live field text first (it may be outside the walk's node/char limits),
            // then every on-screen window of the app.
            val texts = LinkedHashSet<String>()
            var nodes = 0
            var chars = 0
            pending.fieldText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val clipped = it.take(ScanConfig.TEXT_MAX_CHARS)
                if (texts.add(clipped)) chars += clipped.length
            }
            for (root in windows.roots) {
                val e = TextExtractor.collectDetailed(AccessibilityTextNode(root), ScanConfig.TEXT_MAX_NODES, ScanConfig.TEXT_MAX_CHARS)
                texts += e.texts
                nodes += e.nodesVisited
                chars += e.chars
            }
            ScanStatus.onTextCheck(System.currentTimeMillis())
            val found = texts.flatMap { t -> matcher.find(t).map { m -> t to m } }
            if (diagnose) GuardianLog.i(
                applicationContext,
                "Text check fired for $pkg (${pending.events} events, ${pending.fieldEvents} from a text field): " +
                    "field text ${pending.fieldText?.length ?: 0} chars, ${windows.roots.size} app window(s), " +
                    "$nodes nodes, $chars chars read (active window: ${windows.activePackage ?: "none"}) → " +
                    if (found.isEmpty()) "no match." else "matched [${found.map { it.second.term }.distinct().joinToString()}]."
            )
            if (found.isNotEmpty()) onTextMatched(pkg, found)
        } catch (t: Throwable) {
            logFailureRateLimited("text-check", "Text scan: check failed.", t)
        } finally {
            windows.roots.forEach { releaseNode(it) }
        }
    }

    /** Root nodes of [pkg]'s on-screen windows, active first ([TextWindows]), and the active window's package. */
    private class AppWindows(val roots: List<AccessibilityNodeInfo>, val activePackage: String?)

    private fun textWindows(pkg: String): AppWindows {
        val infos = runCatching { windows }.getOrNull().orEmpty()
        if (infos.isEmpty()) {
            // No window list (shouldn't happen with flagRetrieveInteractiveWindows): active window only.
            val root = runCatching { rootInActiveWindow }.getOrNull() ?: return AppWindows(emptyList(), null)
            val rootPkg = root.packageName?.toString()
            if (rootPkg == pkg) return AppWindows(listOf(root), rootPkg)
            releaseNode(root)
            return AppWindows(emptyList(), rootPkg)
        }
        val roots = infos.map { runCatching { it.root }.getOrNull() }
        val candidates = infos.mapIndexed { i, w ->
            TextWindows.Candidate(
                roots[i]?.packageName?.toString(),
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                w.isActive
            )
        }
        val chosen = TextWindows.select(candidates, pkg).toSet()
        val active = candidates.firstOrNull { it.isActive }?.packageName
        roots.forEachIndexed { i, r -> if (r != null && i !in chosen) releaseNode(r) }
        return AppWindows(chosen.sortedByDescending { candidates[it].isActive }.mapNotNull { roots[it] }, active)
    }

    private fun onTextMatched(pkg: String, found: List<Pair<String, KeywordMatcher.Match>>) {
        val ctx = applicationContext
        val terms = found.map { it.second.term }.distinct()
        val tier = KeywordTier.of(found.map { it.second })
        val fingerprints = terms.map { TextFingerprint.of(pkg, it) }
        if (!textCooldown.shouldReportAny(SystemClock.elapsedRealtime(), fingerprints)) {
            // Same terms in the same app reported within the cooldown: no notification or
            // log entry, but an explicit-tier repeat still locks
            // (ScanConfig.LOCK_ON_SUPPRESSED_REPEATS). A borderline repeat doesn't reopen
            // corroboration: a lingering page would keep image checks running every second;
            // the regular image scan still covers it.
            ScanStatus.textSuppressedCount++
            if (ScanConfig.LOCK_ON_SUPPRESSED_REPEATS && tier == KeywordTier.EXPLICIT) {
                handleLock(
                    lockController?.onDetection(
                        DetectionEvent(
                            System.currentTimeMillis(), 1f, TriggerSource.TEXT, pkg, null,
                            kind = DetectionKind.TEXT, matchedTerms = terms, textTier = tier
                        )
                    )
                )
            }
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
            textSnippet = snippet,
            textTier = tier
        )
        runCatching { DetectionStore.appendMetadata(ctx, event) }
            .onFailure { GuardianLog.e(ctx, "Text scan: failed to write detection metadata.", it) }
        ScanStatus.textDetectionCount++

        // The snippet goes to the review log only; the event log names the terms.
        GuardianLog.w(
            ctx,
            "CONFIRMED text detection: terms=[${terms.joinToString()}] tier=${tier.label} app=$pkg matches=${found.size} " +
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

    /** [RegionNode] over a live accessibility node (children are fetched lazily). */
    private class AccessibilityRegionNode(private val node: AccessibilityNodeInfo) : RegionNode {
        override val className: CharSequence? get() = node.className
        override val viewId: String? get() = node.viewIdResourceName
        override val contentDescription: CharSequence? get() = node.contentDescription
        override val isVisibleToUser: Boolean get() = node.isVisibleToUser
        override val boundsInScreen: Box
            get() = android.graphics.Rect().also { node.getBoundsInScreen(it) }.let { Box(it.left, it.top, it.right, it.bottom) }
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): RegionNode? = node.getChild(index)?.let { AccessibilityRegionNode(it) }
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
        FastScanSettings.removeListener(fastScanListener)
        DetectionBus.unregister(lockListener)
        if (userPresentRegistered) {
            runCatching { unregisterReceiver(userPresentReceiver) }
            userPresentRegistered = false
        }
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
            regionCache.clear()
            lockController = null
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
    /** Region scanning: regions run through the model, and regions whose score came from the cache. */
    @Volatile var regionsClassified = 0L
    @Volatile var regionCacheHits = 0L

    // Stage 5: lock
    @Volatile var lockCount = 0
        private set
    @Volatile var lastLockAtMs = 0L
        private set
    @Volatile var lastLockSource: String? = null
        private set
    @Volatile var relockCount = 0
    @Volatile var lockSkippedCount = 0

    fun onLock(atMs: Long, source: String) {
        lockCount++
        lastLockAtMs = atMs
        lastLockSource = source
    }

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
