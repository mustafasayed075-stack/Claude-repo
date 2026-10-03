package com.personal.guardian

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Rafiq ("رفيق") is Arabic-first: the UI is always Arabic and right-to-left,
 * regardless of the phone's language. Per-app locales (AppCompat 1.6+) make this
 * stick across process restarts without touching any detection/lock logic.
 */
class RafiqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        }
    }
}
