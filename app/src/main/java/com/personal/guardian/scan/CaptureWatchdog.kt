package com.personal.guardian.scan

/**
 * Tracks the one screenshot request that may be in flight, so a request whose callback
 * never arrives can be expired instead of blocking every later capture.
 *
 * Each request gets a token. Exactly one of these ends it:
 *  - [complete]: its callback arrived — the caller clears its in-flight flag;
 *  - [expire]: [timeoutMs] passed with no callback — the caller clears the flag and logs
 *    "capture timeout".
 * A callback that arrives after its request expired (or after a newer request started)
 * is *late*: [complete] returns false so it can't clear the newer request's flag.
 *
 * Pure Kotlin with injected timestamps; single-threaded use (scanner worker thread).
 */
class CaptureWatchdog(val timeoutMs: Long = ScanConfig.CAPTURE_TIMEOUT_MS) {

    init {
        require(timeoutMs > 0) { "timeoutMs must be > 0" }
    }

    private var lastToken = 0
    private var pendingToken: Int? = null
    private var startedAtMs = 0L

    /** Requests that expired without a callback, since creation. */
    var timeouts = 0
        private set

    val isPending: Boolean get() = pendingToken != null

    /** A request starts at [nowMs]; returns its token. */
    fun start(nowMs: Long): Int {
        lastToken++
        pendingToken = lastToken
        startedAtMs = nowMs
        return lastToken
    }

    /** The callback for [token] arrived. True if it is the pending request (not late). */
    fun complete(token: Int): Boolean {
        if (pendingToken != token) return false
        pendingToken = null
        return true
    }

    /**
     * The timeout for [token] fired at [nowMs]. True if that request is still pending
     * and its time is up — it is now expired and the caller should clear in-flight.
     */
    fun expire(token: Int, nowMs: Long): Boolean {
        if (pendingToken != token || nowMs - startedAtMs < timeoutMs) return false
        pendingToken = null
        timeouts++
        return true
    }

    /** How long the pending request has been waiting, or null if none. */
    fun pendingForMs(nowMs: Long): Long? = if (pendingToken == null) null else nowMs - startedAtMs
}
