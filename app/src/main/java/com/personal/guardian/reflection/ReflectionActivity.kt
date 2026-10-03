package com.personal.guardian.reflection

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.personal.guardian.R
import com.personal.guardian.util.GuardianLog

/**
 * Stage 6 — the full-screen Reflection Mode screen. Pins itself with
 * `startLockTask()`, shows the randomly-picked reminder with a countdown, ignores the
 * back button until the time is up, then unpins and finishes.
 *
 * The timing and the back rule live in [ReflectionCountdown] (unit-tested); this
 * activity is the thin Android shell around it.
 */
class ReflectionActivity : AppCompatActivity() {

    private lateinit var countdown: ReflectionCountdown
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var lockTaskStarted = false
    private var endedNormally = false

    private val tick = object : Runnable {
        override fun run() {
            val left = countdown.remainingSeconds()
            findViewById<android.widget.TextView>(R.id.txtCountdown).text =
                getString(R.string.reflection_countdown, left)
            if (countdown.isElapsed()) endNormally() else ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverKeyguardAndTurnScreenOn()
        setContentView(R.layout.activity_reflection)

        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, ReflectionDuration.DEFAULT_SECONDS * 1000)
            .coerceAtLeast(ReflectionDuration.MIN_SECONDS * 1000) // defence in depth: never below the minimum
        countdown = ReflectionCountdown(durationMs, SystemClock::elapsedRealtime)

        bindContent()

        // Back does nothing until the countdown elapses.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!countdown.onBackPressed()) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (!lockTaskStarted) {
            // Pins the app. Without Device Owner this is "screen pinning" the user can
            // still exit via Back+Recents (documented limitation).
            runCatching { startLockTask() }.onFailure {
                GuardianLog.w(this, "Reflection Mode: startLockTask() failed (continuing unpinned).", it)
            }
            lockTaskStarted = true
        }
    }

    override fun onResume() {
        super.onResume()
        countdown.start()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onStop() {
        super.onStop()
        // If we're stopped before the time is up and not finishing on our own terms,
        // the user escaped the pin (Back+Recents) or otherwise left. Auditable.
        if (!endedNormally && !isFinishing && !countdown.isElapsed()) {
            GuardianLog.w(
                this,
                "Reflection Mode ended (user escaped via pin-exit) after ${countdown.elapsedMs() / 1000}s of " +
                    "${countdown.durationMs / 1000}s, detectionId=${intent.getStringExtra(EXTRA_DETECTION_ID)}."
            )
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        player?.runCatching { stop() }
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun endNormally() {
        if (endedNormally) return
        endedNormally = true
        ui.removeCallbacks(tick)
        GuardianLog.i(
            this,
            "Reflection Mode ended (elapsed), duration=${countdown.durationMs / 1000}s, " +
                "detectionId=${intent.getStringExtra(EXTRA_DETECTION_ID)}."
        )
        ReflectionLauncher.dismiss(this)
        runCatching { stopLockTask() }
        finish()
    }

    private fun bindContent() {
        val type = ReflectionType.fromLabel(intent.getStringExtra(EXTRA_TYPE).orEmpty())
        val value = intent.getStringExtra(EXTRA_VALUE).orEmpty()
        val textView = findViewById<android.widget.TextView>(R.id.txtContent)
        val imageView = findViewById<android.widget.ImageView>(R.id.imgContent)
        val videoView = findViewById<android.widget.VideoView>(R.id.videoContent)
        val audioVisual = findViewById<View>(R.id.audioVisual)

        when (type) {
            ReflectionType.TEXT -> textView.apply { visibility = View.VISIBLE; text = value }
            ReflectionType.IMAGE -> imageView.apply {
                visibility = View.VISIBLE
                runCatching { setImageURI(Uri.parse(value)) }.onFailure { showError(textView) }
            }
            ReflectionType.VIDEO -> bindVideo(videoView, textView, value)
            ReflectionType.AUDIO -> bindAudio(audioVisual, textView, value)
            null -> showError(textView)
        }
    }

    private fun bindVideo(videoView: android.widget.VideoView, textView: android.widget.TextView, value: String) {
        videoView.visibility = View.VISIBLE
        runCatching {
            videoView.setVideoURI(Uri.parse(value))
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f) // muted: autoplay-safe
                videoView.start()
            }
            videoView.setOnErrorListener { _, _, _ -> showError(textView); true }
        }.onFailure { showError(textView) }
    }

    private fun bindAudio(audioVisual: View, textView: android.widget.TextView, value: String) {
        audioVisual.visibility = View.VISIBLE
        // Simple visual while audio plays: pulse the audio icon.
        audioVisual.animate().alpha(0.3f).setDuration(800).withEndAction(object : Runnable {
            override fun run() {
                if (isFinishing) return
                audioVisual.animate().alpha(1f).setDuration(800).withEndAction {
                    if (!isFinishing) audioVisual.animate().alpha(0.3f).setDuration(800).withEndAction(this).start()
                }.start()
            }
        }).start()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(this@ReflectionActivity, Uri.parse(value))
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> showError(textView); true }
                prepareAsync()
            }
        }.onFailure { showError(textView) }
    }

    private fun showError(textView: android.widget.TextView) {
        textView.visibility = View.VISIBLE
        textView.text = getString(R.string.reflection_content_error)
    }

    @Suppress("DEPRECATION")
    private fun showOverKeyguardAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_VALUE = "value"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_DETECTION_ID = "detection_id"

        fun intent(context: Context, item: ReflectionItem, durationMs: Long, source: String, detectionId: String): Intent =
            Intent(context, ReflectionActivity::class.java).apply {
                putExtra(EXTRA_TYPE, item.type.label)
                putExtra(EXTRA_VALUE, item.value)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_SOURCE, source)
                putExtra(EXTRA_DETECTION_ID, detectionId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
    }
}
