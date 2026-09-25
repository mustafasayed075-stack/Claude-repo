package com.personal.guardian.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.personal.guardian.service.GuardianForegroundService
import com.personal.guardian.util.GuardianLog

/**
 * Stage 1 — Device Admin / Device Owner receiver.
 *
 * This is the component named in the provisioning command:
 *
 *     adb shell dpm set-device-owner com.personal.guardian/.admin.GuardianDeviceAdminReceiver
 *
 * Responsibilities:
 *  - React to admin activation/deactivation lifecycle events.
 *  - Log **every** deactivation attempt locally with a timestamp (Definition of Done).
 *  - (Re)start the persistent core service whenever the admin is enabled.
 *
 * Removal of a Device Owner is only possible via a full factory reset; the normal
 * "deactivate" path is therefore blocked by the system for a Device Owner. We still
 * implement [onDisableRequested] so that on builds/paths where a disable prompt can
 * surface (e.g. plain device-admin activation before owner promotion), the attempt
 * is recorded.
 */
class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        GuardianLog.i(context, "Device admin ENABLED. Device owner=${isDeviceOwner(context)}")
        // Ensure the core service is up as soon as we hold privileges.
        GuardianForegroundService.start(context)
    }

    /**
     * Called when the user attempts to disable device administration for this app.
     * Returning a non-null warning string is our chance to record the attempt and
     * surface a message. For a Device Owner the system does not expose this path in
     * normal Settings, but recording it here covers every route that can reach it.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        GuardianLog.w(
            context,
            "DEACTIVATION ATTEMPT: user requested to disable device admin " +
                "(deviceOwner=${isDeviceOwner(context)})."
        )
        return "Guardian is a personal accountability tool. Disabling it removes on-device " +
            "content protection. This attempt has been logged."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        GuardianLog.w(context, "DEACTIVATION COMPLETED: device admin was disabled.")
    }

    companion object {
        /** Convenience accessor for the receiver's ComponentName. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, GuardianDeviceAdminReceiver::class.java)

        fun dpm(context: Context): DevicePolicyManager =
            context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        /** True once `dpm set-device-owner` has succeeded for this package. */
        fun isDeviceOwner(context: Context): Boolean =
            dpm(context).isDeviceOwnerApp(context.packageName)

        /** True if this app is at least an active device admin (owner implies admin). */
        fun isAdminActive(context: Context): Boolean =
            dpm(context).isAdminActive(componentName(context))
    }
}
