package com.personal.guardian.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The app in front is corrected from the active window (Build 42: stuck on the launcher). */
class ForegroundReconcilerTest {

    private val own = "com.personal.guardian"
    private val overlays = setOf("com.android.systemui", "com.google.android.inputmethod.latin")

    private fun fix(current: String?, active: String?, lock: Boolean = false, reflection: Boolean = false) =
        ForegroundReconciler.correction(current, active, own, overlays, lock, reflection)

    @Test
    fun stuckOnTheLauncherWhileWhatsAppIsActiveIsCorrected() {
        // The Build 42 case: a launcher/Google peek set the app in front; WhatsApp is active.
        assertEquals("com.whatsapp", fix(current = "com.android.launcher", active = "com.whatsapp"))
        assertEquals("com.whatsapp", fix(current = "com.google.android.googlequicksearchbox", active = "com.whatsapp"))
    }

    @Test
    fun aRealMoveToTheHomeScreenIsAlsoFollowed() {
        assertEquals("com.android.launcher", fix(current = "com.whatsapp", active = "com.android.launcher"))
    }

    @Test
    fun nothingToDoWhenTheyAgreeOrTheActiveWindowIsUnknown() {
        assertNull(fix(current = "com.whatsapp", active = "com.whatsapp"))
        assertNull(fix(current = "com.whatsapp", active = null))
        assertNull(fix(current = "com.whatsapp", active = ""))
    }

    @Test
    fun theShadeAndKeyboardNeverReplaceTheApp() {
        assertNull(fix(current = "com.whatsapp", active = "com.android.systemui"))
        assertNull(fix(current = "com.whatsapp", active = "com.google.android.inputmethod.latin"))
    }

    @Test
    fun rafiqsOwnWindowOnlyCountsDuringALockOrReflection() {
        assertNull(fix(current = "com.whatsapp", active = own))
        assertEquals(own, fix(current = "com.whatsapp", active = own, lock = true))
        assertEquals(own, fix(current = "com.whatsapp", active = own, reflection = true))
    }
}
