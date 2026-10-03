package com.personal.guardian

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.personal.guardian.admin.GuardianDeviceAdminReceiver
import com.personal.guardian.blocklist.BlocklistManager
import com.personal.guardian.blocklist.BlocklistUpdateWorker
import com.personal.guardian.databinding.ActivityMainBinding
import com.personal.guardian.reflection.ReflectionLauncher
import com.personal.guardian.reflection.ReflectionSettings
import com.personal.guardian.reflection.ReflectionSettingsActivity
import com.personal.guardian.scan.DetectionStore
import com.personal.guardian.scan.FastScanSettings
import com.personal.guardian.scan.GuardianAccessibilityService
import com.personal.guardian.scan.ScanConfig
import com.personal.guardian.scan.ScanStatus
import com.personal.guardian.service.GuardianForegroundService
import com.personal.guardian.util.GuardianLog
import com.personal.guardian.vpn.GuardianVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen — the friendly "رفيق" status & setup surface.
 *
 * A big status card ("رفيق شغّال" / "محتاج تفعيل"), a 3-step setup checklist
 * (protection / lock permission / site filtering), and a short settings list
 * (watched apps, reminders, lock duration). The old technical status lines, event
 * log, diagnostics and the adb provisioning command are moved into a hidden
 * "للمطورين" section, revealed by tapping the version 7 times.
 *
 * UI only — every action calls the exact same logic as before.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var versionTaps = 0

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
        applyInsets()

        // First run: show the welcome screen once.
        if (WelcomeActivity.shouldShow(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }

        // The core service should be running whenever the app is used.
        GuardianForegroundService.start(this)
        // If already provisioned as Device Owner, make sure Guardian is allowlisted for
        // lock task so Reflection Mode's pin is the non-exitable kind.
        GuardianDeviceAdminReceiver.allowlistForLockTask(this)
        BlocklistManager.ensureLoaded(this)
        maybeTriggerFirstRefresh()
        maybeRequestNotificationPermission()

        // Status card CTA → the first step that still needs the user.
        binding.btnStatusAction.setOnClickListener { startFirstMissingStep() }

        // Checklist rows → the matching system setting.
        binding.rowProtection.setOnClickListener { openAccessibility() }
        binding.rowAdmin.setOnClickListener { onRequestAdminClicked() }
        binding.rowDns.setOnClickListener { onEnableVpnClicked() }

        // Settings list.
        binding.rowFastScan.setOnClickListener { startActivity(Intent(this, FastScanAppsActivity::class.java)) }
        binding.rowReflection.setOnClickListener { startActivity(Intent(this, ReflectionSettingsActivity::class.java)) }
        binding.rowLockDuration.setOnClickListener { showLockDurationDialog() }

        binding.btnFullScreenIntentSettings.setOnClickListener { ReflectionLauncher.openFullScreenIntentSettings(this) }

        // Developer section: 7 taps on the version reveals it.
        binding.txtVersion.setOnClickListener { onVersionTapped() }
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

    /** Edge-to-edge (SDK 35): pad the toolbar for the status bar and the content for the nav bar. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = bars.top)
            binding.contentMain.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    // ---- setup steps ----

    private fun isProtectionOn() = GuardianAccessibilityService.isEnabledInSettings(this)
    private fun isAdminOn() = GuardianDeviceAdminReceiver.isAdminActive(this)
    private fun isDnsOn() = VpnService.prepare(this) == null

    private fun startFirstMissingStep() {
        when {
            !isProtectionOn() -> openAccessibility()
            !isAdminOn() -> onRequestAdminClicked()
            !isDnsOn() -> onEnableVpnClicked()
            else -> refreshStatus()
        }
    }

    private fun openAccessibility() = startActivity(GuardianAccessibilityService.settingsIntent())

    private fun maybeTriggerFirstRefresh() {
        if (!BlocklistManager.cacheFile(this).exists()) {
            BlocklistUpdateWorker.refreshNow(this)
        }
    }

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
            GuardianVpnService.start(this)
            refreshStatus()
        }
    }

    private fun onRequestAdminClicked() {
        if (GuardianDeviceAdminReceiver.isAdminActive(this)) {
            refreshStatus()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, GuardianDeviceAdminReceiver.componentName(this@MainActivity))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
        }
        adminLauncher.launch(intent)
    }

    /** Lock/Reflection duration — the same persisted value, with the 30 s floor in code. */
    private fun showLockDurationDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(ReflectionSettings.durationSeconds(this@MainActivity).toString())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_duration_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.reflection_duration_save) { _, _ ->
                val requested = input.text?.toString()?.trim()?.toLongOrNull()
                if (requested == null) {
                    Toast.makeText(this, R.string.reflection_duration_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    val clamp = ReflectionSettings.setDurationSeconds(this, requested)
                    val msg = if (clamp.adjusted) getString(R.string.reflection_duration_clamped, clamp.seconds)
                    else getString(R.string.reflection_duration_saved, clamp.seconds)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onVersionTapped() {
        if (binding.cardDeveloper.visibility == View.VISIBLE) return
        versionTaps++
        if (versionTaps >= 7) {
            binding.cardDeveloper.visibility = View.VISIBLE
            Toast.makeText(this, R.string.developer_unlocked, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- state rendering ----

    private fun refreshStatus() {
        val protection = isProtectionOn()
        val admin = isAdminOn()
        val dns = isDnsOn()
        val allOn = protection && admin && dns

        // Big status card.
        binding.txtStatusTitle.setText(if (allOn) R.string.status_card_active_title else R.string.status_card_setup_title)
        binding.txtStatusSubtitle.setText(if (allOn) R.string.status_card_active_subtitle else R.string.status_card_setup_subtitle)
        binding.imgStatus.setImageResource(if (allOn) R.drawable.ic_check_circle else R.drawable.ic_alert_circle)
        binding.btnStatusAction.visibility = if (allOn) View.GONE else View.VISIBLE

        // Checklist.
        step(protection, binding.imgProtection, binding.txtProtectionState)
        step(admin, binding.imgAdmin, binding.txtAdminState)
        step(dns, binding.imgDns, binding.txtDnsState)

        // Settings summaries.
        val fastScan = FastScanSettings.get(this)
        binding.txtFastScanSummary.text = getString(R.string.row_fast_scan_summary, fastScan.size)
        binding.txtReflectionSummary.text = getString(R.string.row_reflection_summary, ReflectionSettings.library(this).size)
        binding.txtLockDurationSummary.text = getString(R.string.row_lock_duration_summary, ReflectionSettings.durationSeconds(this))

        // Warnings.
        val fsiMissing = ReflectionLauncher.needsFullScreenIntentGrant(this)
        binding.txtFullScreenIntentStatus.visibility = if (fsiMissing) View.VISIBLE else View.GONE
        binding.btnFullScreenIntentSettings.visibility = if (fsiMissing) View.VISIBLE else View.GONE
        val notificationsOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        binding.txtNotificationStatus.visibility = if (notificationsOk) View.GONE else View.VISIBLE

        binding.txtVersion.text = getString(R.string.version_label, appVersionName())

        refreshDeveloper()
    }

    private fun step(done: Boolean, icon: android.widget.ImageView, state: android.widget.TextView) {
        icon.setImageResource(if (done) R.drawable.ic_check_circle else R.drawable.ic_alert_circle)
        state.setText(if (done) R.string.step_done else R.string.step_todo)
        state.setTextColor(ContextCompat.getColor(this, if (done) R.color.rafiq_primary else R.color.rafiq_warn))
    }

    /** The old detailed technical readout, now inside the hidden developer section. */
    private fun refreshDeveloper() {
        binding.txtOwnerStatus.text = getString(R.string.status_owner, yesNo(GuardianDeviceAdminReceiver.isDeviceOwner(this)))
        binding.txtAdminStatus.text = getString(R.string.status_admin, yesNo(isAdminOn()))
        binding.txtVpnStatus.text = getString(R.string.status_vpn, yesNo(isDnsOn()))
        binding.txtBlocklistStatus.text = getString(R.string.status_blocklist, BlocklistManager.size)
        binding.txtProvisionHint.text = getString(
            R.string.provision_hint, packageName, GuardianDeviceAdminReceiver.componentName(this).className
        )

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
            lastSource.label, ScanStatus.lastScore
        )
        binding.txtScanDetails.text = getString(
            R.string.status_scan_details, mode, ScanStatus.framesScanned, last, ScanStatus.confirmedCount,
            ScanStatus.suppressedCount, DetectionStore.thumbnailCount(this),
            ScanStatus.regionsClassified, ScanStatus.regionCacheHits
        )

        val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.US)
        binding.txtTextScanStatus.text = when {
            ScanStatus.textFailed -> getString(R.string.status_text_scan_failed)
            ScanStatus.connected && ScanStatus.textEntries > 0 -> getString(
                R.string.status_text_scan_active, ScanStatus.textEntries, ScanStatus.textChecks,
                if (ScanStatus.lastTextCheckAtMs == 0L) getString(R.string.status_scan_no_frames)
                else hhmmss.format(Date(ScanStatus.lastTextCheckAtMs)),
                ScanStatus.textDetectionCount, ScanStatus.textSuppressedCount
            )
            else -> getString(R.string.status_text_scan_inactive)
        }

        binding.txtLockStatus.text = when {
            !ScanConfig.LOCK_ENABLED -> getString(R.string.status_lock_disabled)
            !isAdminOn() -> getString(R.string.status_lock_no_admin, ScanStatus.lockSkippedCount)
            else -> getString(
                R.string.status_lock_armed, ReflectionSettings.durationSeconds(this),
                ScanStatus.lockCount, ScanStatus.relockCount,
                ScanStatus.lastLockSource?.let {
                    "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ScanStatus.lastLockAtMs))} ($it)"
                } ?: getString(R.string.status_lock_none)
            )
        }

        binding.txtDiagnostics.text = GuardianLog.readDiagnostics(this).takeLast(4000)
            .ifEmpty { getString(R.string.diagnostics_empty) }
        binding.txtLog.text = GuardianLog.readAll(this).takeLast(4000).ifEmpty { getString(R.string.log_empty) }
    }

    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun yesNo(b: Boolean) = if (b) getString(R.string.yes) else getString(R.string.no)
}
