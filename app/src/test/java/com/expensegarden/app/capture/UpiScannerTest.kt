package com.expensegarden.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiScannerTest {
    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"
    private val superMoney = "money.super.payments"

    // Read off GPay's own "Scan any QR code" launcher shortcut; proven on his OnePlus, 2026-11-14.
    private val gpayScanner = ScannerLaunch.Component(
        pkg = gpay, cls = "com.google.nbu.paisa.flutter.gpay.app.LauncherActivity",
        action = "android.intent.action.RUN", category = "android.intent.category.LAUNCHER",
        extraKey = "some unique action key", extraValue = "QR_SCANNER",
    )

    @Test fun `gpay tries its scanner, then its home`() =
        assertEquals(listOf(gpayScanner, ScannerLaunch.AppHome(gpay)), UpiScanner.attempts(gpay))

    @Test fun `phonepe tries its scanner link, then its home`() = assertEquals(
        listOf(ScannerLaunch.DeepLink(phonepe, "phonepe://native?id=scanQR"), ScannerLaunch.AppHome(phonepe)),
        UpiScanner.attempts(phonepe),
    )

    @Test fun `any other app opens on its home`() {
        assertEquals(listOf(ScannerLaunch.AppHome(superMoney)), UpiScanner.attempts(superMoney))
        assertFalse(UpiScanner.opensScanner(superMoney))
        assertTrue(UpiScanner.opensScanner(gpay))
        assertTrue(UpiScanner.opensScanner(phonepe))
    }

    @Test fun `hints say what each app will do`() {
        assertEquals("Opens its scanner directly", UpiScanner.hint(gpay))
        assertEquals("Opens the app — scan from there", UpiScanner.hint(superMoney))
    }

    @Test fun `a saved app that is installed opens`() =
        assertEquals(PersonAction.Open(gpay), UpiScanner.personAction(gpay, listOf(phonepe, gpay)))

    @Test fun `nothing saved asks`() =
        assertEquals(PersonAction.Pick(clearSaved = false), UpiScanner.personAction(null, listOf(gpay)))

    @Test fun `a saved app that was uninstalled asks again and clears it`() =
        assertEquals(PersonAction.Pick(clearSaved = true), UpiScanner.personAction(gpay, listOf(phonepe)))

    @Test fun `no UPI app installed says so`() {
        assertEquals(PersonAction.NoneInstalled, UpiScanner.personAction(null, emptyList()))
        assertEquals(PersonAction.NoneInstalled, UpiScanner.personAction(gpay, emptyList()))
    }
}
