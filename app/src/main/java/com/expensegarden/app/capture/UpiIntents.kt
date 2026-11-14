package com.expensegarden.app.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object UpiIntents {
    /** Opens the UPI app chooser for [uri] (from UpiQr.handoffUri). False when no UPI app is installed. */
    fun launchPayment(context: Context, uri: String): Boolean {
        val pay = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        // createChooser never throws ActivityNotFoundException — probe explicitly instead.
        // (resolveActivity's deprecation is acceptable; the replacement needs API 33+.)
        if (context.packageManager.resolveActivity(pay, 0) == null) {
            Toast.makeText(context, "No UPI app found", Toast.LENGTH_LONG).show()
            return false
        }
        context.startActivity(Intent.createChooser(pay, "Pay with"))
        return true
    }
}
