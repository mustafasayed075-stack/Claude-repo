package com.personal.guardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.personal.guardian.MainActivity
import com.personal.guardian.R
import com.personal.guardian.blocklist.BlocklistUpdateWorker
import com.personal.guardian.util.GuardianLog
import com.personal.guardian.vpn.GuardianVpnController

/**
 * Stage 1 — the core persistent foreground service.
 *
 * This service is the long-lived anchor of the whole app. In this stage it:
 *  - Runs persistently as a foreground service with an ongoing notification, so the
 *    OS keeps it alive and the user can always see the tool is active.
 *  - Schedules the periodic blocklist refresh (Stage 2).
 *  - Brings up the DNS blocking VPN (Stage 2).
 *
 * It is deliberately structured as a set of small "bring up" steps so later stages
 * (screen scanning, text detection, lock mechanism, reminder trigger, persistence)
 * can hook in here without reworking the lifecycle. Extend [onServiceReady] rather
 * than [onStartCommand] when adding future subsystems.
 *
 * It extends [LifecycleService] so future stages can launch lifecycle-scoped
 * coroutines and observe lifecycle-aware components from within the service.
 */
class GuardianForegroundService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        GuardianLog.i(this, "Core foreground service creating.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Enter the foreground immediately so the process is protected from being
        // killed and we satisfy the foreground-service start requirements.
        startForeground(NOTIFICATION_ID, buildNotification())
        GuardianLog.i(this, "Core foreground service started (persistent).")

        onServiceReady()

        // START_STICKY: if the system kills us under memory pressure, recreate the
        // service as soon as resources allow. Combined with the boot receiver this
        // keeps the tool effectively always-on.
        return START_STICKY
    }

    /**
     * Single place where subsystems are brought up once the service is in the
     * foreground. Later stages add their initializers here.
     */
    private fun onServiceReady() {
        // Stage 2: make sure the blocklist is kept fresh in the background.
        BlocklistUpdateWorker.ensureScheduled(applicationContext)

        // Stage 2: bring up DNS blocking. Starting is best-effort — if VPN consent
        // has not yet been granted, MainActivity handles the one-time prompt.
        GuardianVpnController.startIfPermitted(applicationContext)

        // Later stages will add, e.g.:
        //   ScreenScanController.start(this)
        //   LockController.attach(this)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null // Not a bound service.
    }

    override fun onDestroy() {
        GuardianLog.w(this, "Core foreground service destroyed; will be restarted (sticky/boot).")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.core_service_channel_name),
            NotificationManager.IMPORTANCE_LOW // low: persistent, quiet, no sound
        ).apply {
            description = getString(R.string.core_service_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.core_service_notification_title))
            .setContentText(getString(R.string.core_service_notification_text))
            .setSmallIcon(R.drawable.ic_guardian_shield)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guardian_core_service"
        private const val NOTIFICATION_ID = 1001

        /** Starts the core service, using the foreground-start API on O+. */
        fun start(context: Context) {
            val intent = Intent(context, GuardianForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
