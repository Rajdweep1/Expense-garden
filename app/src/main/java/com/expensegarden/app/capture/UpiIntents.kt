package com.expensegarden.app.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.expensegarden.app.core.Money

object UpiIntents {
    /** Launches the UPI app chooser. Returns false when no UPI app is installed. */
    fun launchPayment(context: Context, vpa: String, payeeName: String?, amountPaise: Long, note: String?): Boolean {
        val uri = Uri.Builder()
            .scheme("upi").authority("pay")
            .appendQueryParameter("pa", vpa)
            .apply { if (!payeeName.isNullOrBlank()) appendQueryParameter("pn", payeeName) }
            .appendQueryParameter("am", Money.intentAmount(amountPaise))
            .appendQueryParameter("cu", "INR")
            .apply { if (!note.isNullOrBlank()) appendQueryParameter("tn", note) }
            .build()
        val pay = Intent(Intent.ACTION_VIEW, uri)
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
