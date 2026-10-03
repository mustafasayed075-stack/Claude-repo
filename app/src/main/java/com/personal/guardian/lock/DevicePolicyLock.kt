package com.personal.guardian.lock

import android.content.Context
import com.personal.guardian.admin.GuardianDeviceAdminReceiver

/**
 * [DeviceLock] over the Stage 1 device admin: `DevicePolicyManager.lockNow()` needs
 * Guardian to be an active admin (the "Activate device admin" button, or Device
 * Owner provisioning) with the `force-lock` policy (device_admin_policies.xml).
 */
class DevicePolicyLock(context: Context) : DeviceLock {
    private val context = context.applicationContext

    override fun isAdminActive(): Boolean = GuardianDeviceAdminReceiver.isAdminActive(context)

    override fun lockNow() = GuardianDeviceAdminReceiver.dpm(context).lockNow()
}
