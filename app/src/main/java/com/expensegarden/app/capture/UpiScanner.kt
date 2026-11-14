package com.expensegarden.app.capture

/** One way to open a UPI app. Plain data, so the choice and its order are JVM-tested; UpiApps
 *  turns these into Intents. */
sealed interface ScannerLaunch {
    data class Component(
        val pkg: String, val cls: String, val action: String, val category: String,
        val extraKey: String, val extraValue: String,
    ) : ScannerLaunch
    data class DeepLink(val pkg: String, val uri: String) : ScannerLaunch
    data class AppHome(val pkg: String) : ScannerLaunch
}

/** What a person's QR does once the pre-payment check lets it through. */
sealed interface PersonAction {
    data class Open(val pkg: String) : PersonAction
    /** Ask which app; [clearSaved] when the saved one was uninstalled (or restored onto a phone without it). */
    data class Pick(val clearSaved: Boolean) : PersonAction
    data object NoneInstalled : PersonAction
}

/** UPI apps refuse person-to-person payments started by another app (phone E2E, 2026-11-14), so a
 *  person's QR opens the user's chosen UPI app instead — on its own QR scanner where we know how.
 *
 *  The scanner launches are each app's own launcher shortcut ("Scan any QR"), read off
 *  `dumpsys shortcut` and proven on his OnePlus (Android 16) with `adb am start`. They are
 *  undocumented, so every one is followed by the app's plain launch as a fallback, and an app is
 *  added here only after it has been tried on a real phone. */
object UpiScanner {
    private const val GPAY = "com.google.android.apps.nbu.paisa.user"
    private const val PHONEPE = "com.phonepe.app"

    private val scanners: Map<String, ScannerLaunch> = mapOf(
        GPAY to ScannerLaunch.Component(
            pkg = GPAY, cls = "com.google.nbu.paisa.flutter.gpay.app.LauncherActivity",
            action = "android.intent.action.RUN", category = "android.intent.category.LAUNCHER",
            extraKey = "some unique action key", extraValue = "QR_SCANNER",
        ),
        PHONEPE to ScannerLaunch.DeepLink(PHONEPE, "phonepe://native?id=scanQR"),
    )

    /** In order: the scanner when known, then the app's own launch. */
    fun attempts(pkg: String): List<ScannerLaunch> = listOfNotNull(scanners[pkg], ScannerLaunch.AppHome(pkg))

    fun opensScanner(pkg: String): Boolean = pkg in scanners

    fun hint(pkg: String): String = if (opensScanner(pkg)) "Opens its scanner directly" else "Opens the app — scan from there"

    fun personAction(saved: String?, installed: List<String>): PersonAction = when {
        installed.isEmpty() -> PersonAction.NoneInstalled
        saved != null && saved in installed -> PersonAction.Open(saved)
        else -> PersonAction.Pick(clearSaved = saved != null)
    }
}
