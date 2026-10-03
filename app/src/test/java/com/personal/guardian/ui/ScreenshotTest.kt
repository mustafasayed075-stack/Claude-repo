package com.personal.guardian.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.personal.guardian.R
import org.junit.Rule
import org.junit.Test

/**
 * Design-review screenshots of the UI as it is redesigned (Rafiq theme, Arabic/RTL).
 * Not a behaviour test — it renders layouts with the app theme in light and dark so
 * each screen can be reviewed before shipping. Dynamic text that the Activity sets at
 * runtime is filled here with representative Arabic values. Run:
 * `./gradlew recordPaparazziDebug`.
 */
class ScreenshotTest {

    private val device = DeviceConfig.PIXEL_5.copy(locale = "ar")

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, theme = "Theme.Rafiq")

    private fun night(on: Boolean) =
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = if (on) NightMode.NIGHT else NightMode.NOTNIGHT))

    private fun shot(name: String, layout: Int, night: Boolean) {
        night(night)
        paparazzi.snapshot(paparazzi.inflate(layout), name)
    }

    /** Fills the home screen's runtime-set text so the preview shows a real state. */
    private fun populateMain(root: View, active: Boolean) {
        fun tv(id: Int) = root.findViewById<TextView>(id)
        fun iv(id: Int) = root.findViewById<ImageView>(id)
        val res = root.resources
        val okIcon = R.drawable.ic_check_circle
        val todoIcon = R.drawable.ic_alert_circle

        tv(R.id.txtStatusTitle).setText(if (active) R.string.status_card_active_title else R.string.status_card_setup_title)
        tv(R.id.txtStatusSubtitle).setText(if (active) R.string.status_card_active_subtitle else R.string.status_card_setup_subtitle)
        iv(R.id.imgStatus).setImageResource(if (active) okIcon else todoIcon)
        root.findViewById<View>(R.id.btnStatusAction).visibility = if (active) View.GONE else View.VISIBLE

        fun step(iconId: Int, stateId: Int, done: Boolean) {
            iv(iconId).setImageResource(if (done) okIcon else todoIcon)
            tv(stateId).setText(if (done) R.string.step_done else R.string.step_todo)
        }
        step(R.id.imgProtection, R.id.txtProtectionState, active)
        step(R.id.imgAdmin, R.id.txtAdminState, active)
        step(R.id.imgDns, R.id.txtDnsState, active)

        tv(R.id.txtFastScanSummary).text = res.getString(R.string.row_fast_scan_summary, 19)
        tv(R.id.txtReflectionSummary).text = res.getString(R.string.row_reflection_summary, 3)
        tv(R.id.txtLockDurationSummary).text = res.getString(R.string.row_lock_duration_summary, 30)
        tv(R.id.txtVersion).text = res.getString(R.string.version_label, "1.0")
    }

    private fun mainShot(name: String, active: Boolean, night: Boolean) {
        night(night)
        val root = paparazzi.inflate<View>(R.layout.activity_main)
        populateMain(root, active)
        paparazzi.snapshot(root, name)
    }

    @Test fun mainSetupLight() = mainShot("main-setup-light", active = false, night = false)
    @Test fun mainActiveLight() = mainShot("main-active-light", active = true, night = false)
    @Test fun mainActiveDark() = mainShot("main-active-dark", active = true, night = true)
    @Test fun reflectionSettingsLight() = shot("reflection-settings-light", R.layout.activity_reflection_settings, night = false)
    @Test fun reflectionSettingsDark() = shot("reflection-settings-dark", R.layout.activity_reflection_settings, night = true)
    @Test fun fastScanLight() = shot("fast-scan-light", R.layout.activity_fast_scan_apps, night = false)
}
