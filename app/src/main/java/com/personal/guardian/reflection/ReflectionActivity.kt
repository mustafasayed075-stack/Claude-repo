package com.personal.guardian.reflection

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    /** Audio-clip player, and the VideoView's own MediaPlayer, so focus ducking can set either's volume. */
    private var player: MediaPlayer? = null
    private var videoPlayer: MediaPlayer? = null
    private var lockTaskStarted = false
    private var endedNormally = false

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val volume = ReflectionMedia.volumeForFocus(change)
        player?.runCatching { setVolume(volume, volume) }
        videoPlayer?.runCatching { setVolume(volume, volume) }
    }

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
        videoPlayer = null
        abandonAudioFocus()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-assert immersive full-screen whenever focus returns (e.g. after a
        // transient system-bar swipe), so the status/nav bars don't linger.
        if (hasFocus) hideSystemBars()
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
                // Play with audio at the device's media volume (not muted). Hold the
                // VideoView's MediaPlayer so audio-focus ducking can adjust it.
                videoPlayer = mp
                requestAudioFocus()
                val v = ReflectionMedia.videoVolume()
                mp.setVolume(v, v)
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
                // Music stream: honours the device's current media volume.
                setAudioAttributes(mediaAttributes())
                isLooping = true
                setOnPreparedListener { requestAudioFocus(); it.start() }
                setOnErrorListener { _, _, _ -> showError(textView); true }
                prepareAsync()
            }
        }.onFailure { showError(textView) }
    }

    private fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    /** Requests audio focus so a reminder's sound pauses or ducks other apps' media. */
    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAttributes())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioFocusRequest = request
            runCatching { audioManager.requestAudioFocus(request) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) }
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
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
        hideSystemBars()
    }

    /**
     * Immersive full-screen: hide the status bar and the navigation/gesture bar so
     * neither the gesture pill nor the notification shade handle is shown, and a swipe
     * only reveals them transiently (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) without
     * leaving Reflection Mode. Combined with screen pinning, this removes the visible
     * exit affordances. The hold-Back+Recents pin-exit itself is an OS feature that
     * only Device Owner can disable (documented limitation).
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
