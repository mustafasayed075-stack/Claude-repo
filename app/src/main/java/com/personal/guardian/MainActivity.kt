package com.personal.guardian

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.personal.guardian.admin.GuardianDeviceAdminReceiver
import com.personal.guardian.blocklist.BlocklistManager
import com.personal.guardian.blocklist.BlocklistUpdateWorker
import com.personal.guardian.databinding.ActivityMainBinding
import com.personal.guardian.service.GuardianForegroundService
import com.personal.guardian.util.GuardianLog
import com.personal.guardian.vpn.GuardianVpnService

/**
 * Status & manual-control screen.
 *
 * The heavy lifting is automatic (Device Owner provisioning via ADB, always-on VPN,
 * boot restart, periodic refresh). This screen exists to:
 *  - show current state (Device Owner? admin active? blocklist size?),
 *  - let the user grant VPN consent on non-owner installs (interactive prompt),
 *  - manually start the service / force a blocklist refresh while testing,
 *  - review the local event log.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            GuardianLog.i(this, "VPN consent granted by user.")
            GuardianVpnService.start(this)
        } else {
            GuardianLog.w(this, "VPN consent denied by user.")
        }
        refreshStatus()
    }

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The core service should be running whenever the app is used.
        GuardianForegroundService.start(this)
        BlocklistManager.ensureLoaded(this)
        maybeTriggerFirstRefresh()

        binding.btnEnableVpn.setOnClickListener { onEnableVpnClicked() }
        binding.btnRequestAdmin.setOnClickListener { onRequestAdminClicked() }
        binding.btnRefreshList.setOnClickListener {
            BlocklistUpdateWorker.refreshNow(this)
            GuardianLog.i(this, "Manual blocklist refresh requested from UI.")
            refreshStatus()
        }
        binding.btnStartService.setOnClickListener {
            GuardianForegroundService.start(this)
            refreshStatus()
        }
        binding.btnRefreshStatus.setOnClickListener { refreshStatus() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun maybeTriggerFirstRefresh() {
        // If there is no cached list yet, kick off an immediate fetch so blocking
        // works without waiting for the first periodic window.
        if (!BlocklistManager.cacheFile(this).exists()) {
            BlocklistUpdateWorker.refreshNow(this)
        }
    }

    private fun onEnableVpnClicked() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnConsentLauncher.launch(intent)
        } else {
            // Already consented (or always-on configured) → just start.
            GuardianVpnService.start(this)
            refreshStatus()
        }
    }

    private fun onRequestAdminClicked() {
        // Interactive device-admin activation. On a fully provisioned device the app
        // is already Device Owner via ADB and this is unnecessary, but it is useful
        // for testing the admin receiver before owner promotion.
        if (GuardianDeviceAdminReceiver.isAdminActive(this)) {
            refreshStatus()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                GuardianDeviceAdminReceiver.componentName(this@MainActivity)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_explanation)
            )
        }
        adminLauncher.launch(intent)
    }

    private fun refreshStatus() {
        val isOwner = GuardianDeviceAdminReceiver.isDeviceOwner(this)
        val isAdmin = GuardianDeviceAdminReceiver.isAdminActive(this)
        val vpnConsent = VpnService.prepare(this) == null

        binding.txtOwnerStatus.text = getString(
            R.string.status_owner,
            yesNo(isOwner)
        )
        binding.txtAdminStatus.text = getString(
            R.string.status_admin,
            yesNo(isAdmin)
        )
        binding.txtVpnStatus.text = getString(
            R.string.status_vpn,
            yesNo(vpnConsent)
        )
        binding.txtBlocklistStatus.text = getString(
            R.string.status_blocklist,
            BlocklistManager.size
        )
        binding.txtProvisionHint.text = getString(
            R.string.provision_hint,
            packageName,
            GuardianDeviceAdminReceiver.componentName(this).className
        )

        // Show the tail of the event log for quick review.
        val log = GuardianLog.readAll(this)
        binding.txtLog.text = log.takeLast(4000).ifEmpty { getString(R.string.log_empty) }
    }

    private fun yesNo(b: Boolean) =
        if (b) getString(R.string.yes) else getString(R.string.no)
}
