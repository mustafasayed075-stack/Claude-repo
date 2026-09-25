package com.personal.guardian.vpn

import android.content.Context
import android.net.VpnService
import android.os.Build
import com.personal.guardian.admin.GuardianDeviceAdminReceiver
import com.personal.guardian.util.GuardianLog

/**
 * Coordinates bringing up the DNS-filtering VPN.
 *
 * The VPN normally needs one-time user consent ([VpnService.prepare]). Because this
 * app is provisioned as **Device Owner** in Stage 1, it can instead configure an
 * always-on, lockdown VPN via [android.app.admin.DevicePolicyManager.setAlwaysOnVpnPackage],
 * which both grants consent and makes the OS start (and keep) the tunnel across
 * reboots — directly serving Stage 2's "starts automatically on device boot".
 *
 * If for some reason the app is not (yet) Device Owner, [startIfPermitted] falls
 * back to starting the service only when consent has already been granted; the UI
 * handles the interactive consent prompt otherwise.
 */
object GuardianVpnController {

    /**
     * Preferred path on a provisioned device: register an always-on, locked-down
     * VPN. Requires Device Owner and API 24+. Returns true if it was applied.
     */
    fun configureAlwaysOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (!GuardianDeviceAdminReceiver.isDeviceOwner(context)) return false
        return try {
            val dpm = GuardianDeviceAdminReceiver.dpm(context)
            // lockdownEnabled = true → no traffic flows if the VPN is down.
            dpm.setAlwaysOnVpnPackage(
                GuardianDeviceAdminReceiver.componentName(context),
                context.packageName,
                /* lockdownEnabled = */ true
            )
            GuardianLog.i(context, "Always-on lockdown VPN configured for ${context.packageName}.")
            true
        } catch (t: Throwable) {
            GuardianLog.e(context, "Failed to configure always-on VPN.", t)
            false
        }
    }

    /**
     * Starts DNS filtering if it is permitted to do so without user interaction:
     *  - As Device Owner, configures always-on (which also starts the tunnel), then
     *    also starts our service so filtering begins immediately.
     *  - Otherwise, starts the service only if consent was already granted
     *    (`VpnService.prepare` returns null).
     */
    fun startIfPermitted(context: Context) {
        if (configureAlwaysOn(context)) {
            GuardianVpnService.start(context)
            return
        }
        val consentNeeded = VpnService.prepare(context) != null
        if (!consentNeeded) {
            GuardianVpnService.start(context)
        } else {
            GuardianLog.w(context, "VPN consent not yet granted; awaiting user approval from UI.")
        }
    }
}
