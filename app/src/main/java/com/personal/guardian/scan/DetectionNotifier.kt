package com.personal.guardian.scan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.personal.guardian.MainActivity
import com.personal.guardian.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary stub reaction for Stage 3 (spec §6): a local notification on each
 * confirmed detection. No lock or blocking action — that is a later stage, which
 * will subscribe to [DetectionBus] instead.
 */
object DetectionNotifier {

    private const val CHANNEL_ID = "guardian_detections"
    private const val NOTIFICATION_ID = 3001

    /** Shows the notification. Returns false if notifications are not permitted. */
    fun show(context: Context, event: DetectionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(event.timestampMs))
        val text = context.getString(
            R.string.detection_notification_text,
            event.confidence,
            event.source.label,
            time
        )
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian_shield)
            .setContentTitle(context.getString(R.string.detection_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            // Sound/vibrate only when first posted; later detections update it
            // silently until the user dismisses it.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        // Same id each time: repeated detections update one notification instead of stacking.
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.detection_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.detection_channel_desc) }
        )
    }
}
