package com.personal.guardian.scan

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.personal.guardian.R
import com.personal.guardian.util.GuardianLog

/**
 * A small on-screen countdown shown while Guardian can't read a blind-spot app
 * (README "Stage 5 — blind-spot escalation"). It self-ticks from a deadline so the
 * number counts down smoothly between captures, and is removed when visibility returns
 * or the lock takes over. Needs the "display over other apps" permission; without it
 * the blind-spot lock still works, just without the visible counter.
 *
 * All window operations run on the main thread (WindowManager requires a Looper thread);
 * the service drives it from its worker thread, so every method posts to the main Handler.
 */
class BlindSpotOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var deadlineMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            val left = BlindSpotCountdown.secondsLeft(deadlineMs, SystemClock.elapsedRealtime())
            v.text = context.getString(R.string.blind_spot_overlay_countdown, left)
            if (left > 0) main.postDelayed(this, 500)
        }
    }

    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Show (or re-target) the countdown ending at [deadlineMs] (elapsedRealtime). */
    fun show(deadlineMs: Long) {
        main.post {
            this.deadlineMs = deadlineMs
            if (view == null) {
                if (!canDraw()) {
                    GuardianLog.i(context, "Blind-spot overlay not shown: \"display over other apps\" permission not granted.")
                    return@post
                }
                val tv = buildView()
                val added = runCatching { wm.addView(tv, layoutParams()) }
                    .onFailure { GuardianLog.w(context, "Blind-spot overlay could not be added.", it) }
                    .isSuccess
                if (added) view = tv else return@post
            }
            main.removeCallbacks(tick)
            main.post(tick)
        }
    }

    /** Remove the countdown (visibility returned, or the lock is taking over). */
    fun hide() {
        main.post {
            main.removeCallbacks(tick)
            view?.let { v -> runCatching { wm.removeView(v) } }
            view = null
        }
    }

    private fun buildView(): TextView {
        val d = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * d).toInt()
        val bg = GradientDrawable().apply {
            cornerRadius = px(16).toFloat()
            setColor(0xE6000000.toInt()) // ~90% black
            setStroke(px(1), 0x33FFFFFF)
        }
        return TextView(context).apply {
            setBackgroundDrawable(bg)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(px(16), px(10), px(16), px(10))
            text = context.getString(R.string.blind_spot_overlay_countdown, 0)
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (context.resources.displayMetrics.density * 96).toInt()
        }
    }
}
