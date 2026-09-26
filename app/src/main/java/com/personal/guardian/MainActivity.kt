package com.personal.guardian

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.personal.guardian.admin.GuardianDeviceAdminReceiver
import com.personal.guardian.blocklist.BlocklistManager
import com.personal.guardian.blocklist.BlocklistUpdateWorker
import com.personal.guardian.databinding.ActivityMainBinding
import com.personal.guardian.scan.DetectionStore
import com.personal.guardian.scan.GuardianAccessibilityService
import com.personal.guardian.scan.ScanStatus
import com.personal.guardian.service.GuardianForegroundService
import com.personal.guardian.util.GuardianLog
import com.personal.guardian.vpn.GuardianVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status & manual-control screen.
 *
 * The heavy lifting is automatic (Device Owner provisioning via ADB, always-on VPN,
 * boot restart, periodic refresh). This screen exists to:
 *  - show current state (Device Owner? admin active? blocklist size? screen
 *    scanning active?),
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        GuardianLog.i(this, "Notification permission ${if (granted) "granted" else "denied"} by user.")
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The core service should be running whenever the app is used.
        GuardianForegroundService.start(this)
        BlocklistManager.ensureLoaded(this)
        maybeTriggerFirstRefresh()
        maybeRequestNotificationPermission()

        binding.btnEnableVpn.setOnClickListener { onEnableVpnClicked() }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(GuardianAccessibilityService.settingsIntent())
        }
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

    /** Stage 3 detection alerts (and the core-service notification) need this on 13+. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        refreshScanStatus()
        binding.txtProvisionHint.text = getString(
            R.string.provision_hint,
            packageName,
            GuardianDeviceAdminReceiver.componentName(this).className
        )

        // Diagnostics have their own file, so the event log's churn can't hide them.
        binding.txtDiagnostics.text = GuardianLog.readDiagnostics(this).takeLast(4000)
            .ifEmpty { getString(R.string.diagnostics_empty) }

        // Show the tail of the event log for quick review.
        val log = GuardianLog.readAll(this)
        binding.txtLog.text = log.takeLast(4000).ifEmpty { getString(R.string.log_empty) }
    }

    private fun refreshScanStatus() {
        val enabled = GuardianAccessibilityService.isEnabledInSettings(this)
        binding.txtScanStatus.text = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                getString(R.string.status_scan_unsupported, Build.VERSION.SDK_INT)
            enabled && ScanStatus.connected && ScanStatus.modelLoaded -> getString(R.string.status_scan_active)
            enabled && ScanStatus.connected -> getString(R.string.status_scan_starting)
            enabled -> getString(R.string.status_scan_enabled_not_running)
            else -> getString(R.string.status_scan_off)
        }

        val fastPkg = ScanStatus.fastModePackage
        val mode = if (fastPkg != null) getString(R.string.status_scan_mode_fast, fastPkg)
        else getString(R.string.status_scan_mode_baseline)
        val lastSource = ScanStatus.lastSource
        val last = if (lastSource == null) getString(R.string.status_scan_no_frames)
        else getString(
            R.string.status_scan_last_frame,
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ScanStatus.lastFrameAtMs)),
            lastSource.label,
            ScanStatus.lastScore
        )
        binding.txtScanDetails.text = getString(
            R.string.status_scan_details,
            mode,
            ScanStatus.framesScanned,
            last,
            ScanStatus.confirmedCount,
            ScanStatus.suppressedCount,
            DetectionStore.thumbnailCount(this)
        )

        // Stage 4: text scanning status.
        val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.US)
        binding.txtTextScanStatus.text = when {
            ScanStatus.textFailed -> getString(R.string.status_text_scan_failed)
            ScanStatus.connected && ScanStatus.textEntries > 0 -> getString(
                R.string.status_text_scan_active,
                ScanStatus.textEntries,
                ScanStatus.textChecks,
                if (ScanStatus.lastTextCheckAtMs == 0L) getString(R.string.status_scan_no_frames)
                else hhmmss.format(Date(ScanStatus.lastTextCheckAtMs)),
                ScanStatus.textDetectionCount,
                ScanStatus.textSuppressedCount
            )
            else -> getString(R.string.status_text_scan_inactive)
        }

        val notificationsOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        binding.txtNotificationStatus.visibility = if (notificationsOk) View.GONE else View.VISIBLE
        binding.txtNotificationStatus.text = getString(R.string.status_notifications_off)
    }

    private fun yesNo(b: Boolean) =
        if (b) getString(R.string.yes) else getString(R.string.no)
}
