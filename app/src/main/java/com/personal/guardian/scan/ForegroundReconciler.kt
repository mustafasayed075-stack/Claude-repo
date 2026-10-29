package com.personal.guardian.scan

/**
 * Keeps "the app in front" honest at each capture by checking it against the window the
 * system reports as **active**.
 *
 * The app in front is otherwise set only from window-state events. Those can come from
 * a window that is not (or no longer) in front: on gesture navigation a brief peek at the
 * home screen (or the Google app's search panel on it) sends window-state events from the
 * launcher / `com.google.android.googlequicksearchbox`, but going back to the app that
 * was open sends none, because its activity never changed state. Guardian then kept
 * attributing every capture to the launcher while WhatsApp was really in front (Build 42
 * log: captures "app=com.android.launcher listed=no" for 40 s while text checks of the
 * same moment saw "active window: com.whatsapp"), so the blind-spot grace never advanced.
 *
 * The active window is not always an app: the notification shade (system UI) and the
 * keyboard can be active, and Rafiq's own windows only count while a lock or Reflection
 * Mode is showing ([ForegroundFilter]). Those never override the current app.
 *
 * Pure Kotlin, so it is unit-tested.
 */
object ForegroundReconciler {

    /**
     * The package to switch the app in front to, or null to keep [current].
     * [active]: package of the active window, if one could be read.
     */
    fun correction(
        current: String?,
        active: String?,
        ownPackage: String,
        overlayPackages: Set<String>,
        lockActive: Boolean,
        reflectionShowing: Boolean
    ): String? {
        if (active.isNullOrEmpty() || active == current) return null
        if (active in overlayPackages) return null // notification shade, keyboards
        if (!ForegroundFilter.counts(active, ownPackage, lockActive, reflectionShowing)) return null
        return active
    }

    /**
     * The app in front **now**, for something that happens between captures (a blocked
     * DNS query): the active window when it counts, else [current]. Without this, a
     * reload right after coming back to the browser from Recents / a home-screen peek
     * was logged under the launcher (no window-state event says the browser is back).
     */
    fun inFront(
        current: String?,
        active: String?,
        ownPackage: String,
        overlayPackages: Set<String>,
        lockActive: Boolean,
        reflectionShowing: Boolean
    ): String? = correction(current, active, ownPackage, overlayPackages, lockActive, reflectionShowing) ?: current
}
