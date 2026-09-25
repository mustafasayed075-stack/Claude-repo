package com.personal.guardian.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personal.guardian.blocklist.BlocklistUpdateWorker
import com.personal.guardian.service.GuardianForegroundService
import com.personal.guardian.util.GuardianLog

/**
 * Restarts the core service and DNS filtering after a reboot.
 *
 * This is what makes the protection survive a restart (Stage 1 DoD: "a device
 * reboot does not undo any of the above") and makes DNS filtering "start
 * automatically on device boot" (Stage 2 DoD). The core service's own startup
 * brings the VPN up and re-affirms the periodic blocklist schedule; as Device
 * Owner the always-on VPN is also restarted by the OS independently.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                GuardianLog.i(context, "Boot completed (${intent.action}); restarting core service.")
                GuardianForegroundService.start(context)
                BlocklistUpdateWorker.ensureScheduled(context)
            }
        }
    }
}
