package com.personal.guardian

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.personal.guardian.util.CrashLogger

/**
 * Rafiq ("رفيق") is Arabic-first: the UI is always Arabic and right-to-left,
 * regardless of the phone's language. Per-app locales (AppCompat 1.6+) make this
 * stick across process restarts without touching any detection/lock logic.
 *
 * It also installs [CrashLogger] first thing, so a crash anywhere in the process
 * (screens, services, the VPN thread) lands in the event log the user can copy.
 */
class RafiqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        }
    }
}
