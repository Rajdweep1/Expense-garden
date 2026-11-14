package com.expensegarden.app.ui

import android.content.Context
import android.widget.Toast
import com.expensegarden.app.capture.PersonAction
import com.expensegarden.app.capture.QrKind
import com.expensegarden.app.capture.ScannerLaunch
import com.expensegarden.app.capture.UpiApp
import com.expensegarden.app.capture.UpiApps
import com.expensegarden.app.capture.UpiIntents
import com.expensegarden.app.capture.UpiQr
import com.expensegarden.app.capture.UpiScanner
import com.expensegarden.app.data.UpiPrefs

sealed interface HandoffOutcome {
    data object Done : HandoffOutcome
    /** A person's QR with no usable UPI app chosen: ask, then [ScanHandoff.openForPerson]. */
    data class NeedPicker(val apps: List<UpiApp>) : HandoffOutcome
}

/** Pay first: hands a scanned QR to a UPI app. The awaiting payment is written immediately before
 *  the app opens and removed when nothing opened (plan Q1), so a return to the app finds exactly
 *  the payments that were really handed off. */
object ScanHandoff {
    fun start(context: Context, vm: MainViewModel, prefs: UpiPrefs, r: PrePayResult): HandoffOutcome {
        val qr = r.payee.raw
        if (UpiQr.kind(qr) == QrKind.MERCHANT) {
            val p = vm.recordHandoff(r) ?: return couldNotSave(context)
            if (!UpiIntents.launchPayment(context, UpiQr.handoffUri(qr))) vm.cancelHandoff(p.id)
            return HandoffOutcome.Done
        }
        // UPI apps refuse person-to-person payments started by another app: open the chosen one,
        // on its scanner where we know how, and the same QR is scanned there.
        val apps = UpiApps.installed(context)
        return when (val action = UpiScanner.personAction(prefs.personAppPackage, apps.map { it.pkg })) {
            PersonAction.NoneInstalled -> {
                Toast.makeText(context, "No UPI app found", Toast.LENGTH_LONG).show()
                HandoffOutcome.Done
            }
            is PersonAction.Open -> {
                val app = apps.first { it.pkg == action.pkg }
                if (openForPerson(context, vm, r, app)) HandoffOutcome.Done
                else {
                    Toast.makeText(context, "Couldn't open ${app.label} — pick another", Toast.LENGTH_LONG).show()
                    HandoffOutcome.NeedPicker(apps)
                }
            }
            is PersonAction.Pick -> {
                if (action.clearSaved) prefs.personAppPackage = null
                HandoffOutcome.NeedPicker(apps)
            }
        }
    }

    /** Nothing opens when the payment couldn't be remembered: paying it would leave no way to log
     *  it (code review, 2026-11-14). */
    private fun couldNotSave(context: Context): HandoffOutcome {
        Toast.makeText(context, "Couldn't save this payment — storage full? Not opening UPI.", Toast.LENGTH_LONG).show()
        return HandoffOutcome.Done
    }

    /** Opens [app] for a person's QR and says what opened. False, with nothing stored, when
     *  nothing could be started. */
    fun openForPerson(context: Context, vm: MainViewModel, r: PrePayResult, app: UpiApp): Boolean {
        val p = vm.recordHandoff(r) ?: run { couldNotSave(context); return true }
        val started = UpiApps.open(context, app.pkg)
        if (started == null) {
            vm.cancelHandoff(p.id)
            return false
        }
        Toast.makeText(context, PendingLabels.openingUpiApp(app.label, scanner = started !is ScannerLaunch.AppHome), Toast.LENGTH_LONG).show()
        return true
    }
}
