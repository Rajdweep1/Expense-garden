package com.expensegarden.app.data

import android.content.Context
import com.expensegarden.app.capture.AwaitingPayment
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Payments handed to a UPI app and not logged yet (pay-first spec). Device state like UpiPrefs,
 *  never synced. Written with commit(), not apply(): the entry must be on disk before the UPI app
 *  opens, because Android may kill this process while the user pays (plan Q1). */
class AwaitingLogStore(context: Context) {
    private val prefs = context.getSharedPreferences("awaiting_log", Context.MODE_PRIVATE)

    fun all(): List<AwaitingPayment> = decode(prefs.getString(KEY, null))

    fun add(p: AwaitingPayment) = write(all().filterNot { it.id == p.id } + p)

    /** Removes and returns the payment, or null when it isn't there. */
    fun remove(id: String): AwaitingPayment? {
        val list = all()
        val hit = list.firstOrNull { it.id == id } ?: return null
        write(list - hit)
        return hit
    }

    /** Its Log it form has been shown: it no longer reopens on return (spec D8). */
    fun markShown(id: String) = write(all().map { if (it.id == id) it.copy(handedOffAt = null) else it })

    /** Undo of "Didn't pay": back as a home card, not a form that reopens itself. */
    fun restore(p: AwaitingPayment) = add(p.copy(handedOffAt = null))

    private fun write(list: List<AwaitingPayment>) {
        prefs.edit().putString(KEY, encode(list)).commit()
    }

    private companion object {
        const val KEY = "payments"

        fun encode(list: List<AwaitingPayment>): String = JSONArray(list.map { p ->
            JSONObject().put("id", p.id).put("vpa", p.vpa).put("payeeName", p.payeeName).put("qrUri", p.qrUri)
                .put("fixedAmountPaise", p.fixedAmountPaise ?: JSONObject.NULL).put("scannedAt", p.scannedAt)
                .put("handedOffAt", p.handedOffAt ?: JSONObject.NULL).put("estimatePaise", p.estimatePaise)
        }).toString()

        /** A corrupt file reads as empty rather than crashing every launch. */
        fun decode(json: String?): List<AwaitingPayment> = try {
            val arr = JSONArray(json ?: return emptyList())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AwaitingPayment(
                    id = o.getString("id"), vpa = o.getString("vpa"), payeeName = o.getString("payeeName"),
                    qrUri = o.getString("qrUri"),
                    fixedAmountPaise = if (o.isNull("fixedAmountPaise")) null else o.getLong("fixedAmountPaise"),
                    scannedAt = o.getLong("scannedAt"),
                    handedOffAt = if (o.isNull("handedOffAt")) null else o.getLong("handedOffAt"),
                    estimatePaise = o.getLong("estimatePaise"),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}
