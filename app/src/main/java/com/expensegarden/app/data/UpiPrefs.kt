package com.expensegarden.app.data

import android.content.Context

/** Which UPI app a person's QR opens (UpiScanner). Device state like GardenPrefs, not ledger data,
 *  so never synced. Left in Auto Backup on purpose: a choice restored onto a phone without that app
 *  is caught by UpiScanner.personAction and asked again. */
class UpiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("upi_prefs", Context.MODE_PRIVATE)

    /** Null = not chosen; setting null removes the key ("None" in Settings). */
    var personAppPackage: String?
        get() = prefs.getString(KEY_PERSON_APP, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_PERSON_APP) else putString(KEY_PERSON_APP, value) }.apply()

    private companion object {
        const val KEY_PERSON_APP = "personAppPackage"
    }
}
