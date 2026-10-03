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

    /** A few reminder cards, so the preview shows the card design (ListView needs a
     *  running adapter Paparazzi doesn't drive, so the cards are inflated directly). */
    private fun reflectionCards(name: String, night: Boolean) {
        night(night)
        val container = android.widget.LinearLayout(paparazzi.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFFF6F3EC.toInt().let { if (night) 0xFF101A19.toInt() else it })
            setPadding(0, 24, 0, 24)
        }
        val samples = listOf(
            Triple(R.string.reflection_type_text, R.drawable.ic_reflection_text, "افتكر ليه بدأت، وإن اللحظة دي بتعدي."),
            Triple(R.string.reflection_type_audio, R.drawable.ic_reflection_audio, "تلاوة.m4a"),
            Triple(R.string.reflection_type_video, R.drawable.ic_reflection_video, "تذكير.mp4"),
        )
        for (s in samples) {
            val card = paparazzi.inflate<View>(R.layout.item_reflection_content)
            card.findViewById<TextView>(R.id.txtType).setText(s.first)
            card.findViewById<TextView>(R.id.txtLabel).text = s.third
            card.findViewById<ImageView>(R.id.imgPreview).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(s.second)
            }
            container.addView(card)
        }
        paparazzi.snapshot(container, name)
    }

    @Test fun mainSetupLight() = mainShot("main-setup-light", active = false, night = false)
    @Test fun mainActiveLight() = mainShot("main-active-light", active = true, night = false)
    @Test fun mainActiveDark() = mainShot("main-active-dark", active = true, night = true)
    private fun reflectionScreen(name: String, night: Boolean) {
        night(night)
        val root = paparazzi.inflate<View>(R.layout.activity_reflection_settings)
        // The Activity adds tabs at runtime; add them here so the preview shows them.
        val tabs = root.findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabs)
        intArrayOf(
            R.string.reflection_tab_all, R.string.reflection_type_text, R.string.reflection_type_image,
            R.string.reflection_type_audio, R.string.reflection_type_video
        ).forEach { tabs.addTab(tabs.newTab().setText(it)) }
        root.findViewById<TextView>(R.id.editDuration).text = "30"
        root.findViewById<TextView>(R.id.txtDurationHint).text =
            root.resources.getString(R.string.reflection_duration_hint, 30)
        paparazzi.snapshot(root, name)
    }

    @Test fun reflectionSettingsLight() = reflectionScreen("reflection-settings-light", night = false)
    @Test fun reflectionSettingsDark() = reflectionScreen("reflection-settings-dark", night = true)
    @Test fun reflectionCardsLight() = reflectionCards("reflection-cards-light", night = false)
    @Test fun fastScanLight() = shot("fast-scan-light", R.layout.activity_fast_scan_apps, night = false)
}
