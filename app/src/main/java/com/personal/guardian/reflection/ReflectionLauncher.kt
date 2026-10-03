package com.personal.guardian.reflection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.personal.guardian.R
import com.personal.guardian.lock.LockSource
import com.personal.guardian.util.GuardianLog
import java.util.Random

/**
 * Launches [ReflectionActivity] on a lock trigger (Stage 6). Besides the existing
 * `lockNow()`, a confirmed detection posts a **high-priority full-screen-intent
 * notification** aimed at the activity, so it comes up immediately and over the
 * keyguard. A random reminder is chosen here so the log can record which type was
 * shown.
 */
object ReflectionLauncher {

    private const val CHANNEL_ID = "guardian_reflection"
    private const val NOTIFICATION_ID = 3002

    /**
     * Picks a random reminder and raises [ReflectionActivity] for it. [durationMs] is
     * the Reflection Mode duration the lock used. Safe to call from the worker thread.
     */
    fun launch(context: Context, source: LockSource, detectionId: String, durationMs: Long, random: Random = Random()) {
        val ctx = context.applicationContext
        val item = ReflectionSettings.pick(ctx, random)
        if (item == null) {
            GuardianLog.w(
                ctx,
                "Reflection Mode not shown: the content library is empty (add reminders in Guardian → Reflection Mode). " +
                    "The device was still locked. detectionId=$detectionId."
            )
            return
        }
        val intent = ReflectionActivity.intent(ctx, item, durationMs, source.label, detectionId)

        ensureChannel(ctx)
        // A full-screen intent needs notification permission (13+) and, on some 14+
        // builds, the USE_FULL_SCREEN_INTENT grant. Without them the notification still
        // posts; the system may show it as a heads-up banner instead of launching.
        val fullScreen = PendingIntent.getActivity(
            ctx, detectionId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian_shield)
            .setContentTitle(ctx.getString(R.string.reflection_notification_title))
            .setContentText(ctx.getString(R.string.reflection_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .build()

        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canNotify) NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)

        // Also start the activity directly: while Guardian's own foreground service is
        // running, a foreground app may start an activity, and this doesn't depend on
        // the full-screen-intent grant. The notification covers the over-keyguard case.
        runCatching { ctx.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
            .onFailure { GuardianLog.w(ctx, "Reflection Mode: direct activity start failed; relying on the full-screen-intent notification.", it) }

        GuardianLog.w(
            ctx,
            "Reflection Mode shown (content=${item.type.label}, duration=${durationMs / 1000}s), " +
                "source=${source.label}, detectionId=$detectionId" +
                (if (!canNotify) " (notifications not permitted; shown via direct start only)" else "") + "."
        )
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    /**
     * True if the full-screen-intent grant is needed on this build and not held. On
     * Android 14 (API 34)+ `USE_FULL_SCREEN_INTENT` is granted at install only for
     * calling/alarm apps; others must send the user to a settings page. Below 34 the
     * manifest permission is enough.
     */
    fun needsFullScreenIntentGrant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return !nm.canUseFullScreenIntent()
    }

    /** Opens the per-app full-screen-intent settings page (Android 14+), else app details. */
    fun openFullScreenIntentSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { GuardianLog.w(context, "Could not open full-screen-intent settings.", it) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reflection_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.reflection_channel_desc) }
        )
    }
}
