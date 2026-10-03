package com.personal.guardian.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.personal.guardian.R
import org.junit.Rule
import org.junit.Test

/**
 * Design-review screenshots of the UI as it is redesigned (Rafiq theme, Arabic/RTL).
 * Not a behaviour test — it renders layouts with the app theme in light and dark so
 * each screen can be reviewed before shipping. Run: `./gradlew recordPaparazziDebug`.
 */
class ScreenshotTest {

    private val device = DeviceConfig.PIXEL_5.copy(locale = "ar")

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = device, theme = "Theme.Rafiq")

    private fun shot(name: String, layout: Int, night: Boolean) {
        paparazzi.unsafeUpdateConfig(device.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT))
        paparazzi.snapshot(paparazzi.inflate(layout), name)
    }

    @Test fun mainLight() = shot("main-light", R.layout.activity_main, night = false)
    @Test fun mainDark() = shot("main-dark", R.layout.activity_main, night = true)
    @Test fun reflectionSettingsLight() = shot("reflection-settings-light", R.layout.activity_reflection_settings, night = false)
    @Test fun reflectionSettingsDark() = shot("reflection-settings-dark", R.layout.activity_reflection_settings, night = true)
    @Test fun fastScanLight() = shot("fast-scan-light", R.layout.activity_fast_scan_apps, night = false)
}
