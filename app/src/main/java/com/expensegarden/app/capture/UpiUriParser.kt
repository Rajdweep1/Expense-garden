package com.expensegarden.app.capture

import com.expensegarden.app.core.Money
import java.net.URLDecoder

data class UpiPayee(
    val vpa: String,
    val name: String?,
    val amountPaise: Long?,
    val note: String?,
    /** The scanned string itself: a merchant QR is handed over as this (UpiQr.handoffUri, which only
     *  strips an `am` the QR doesn't fix validly). */
    val raw: String,
)

object UpiUriParser {
    /** Returns null when [raw] is not a upi://pay URI with a payee address. */
    fun parse(raw: String): UpiPayee? {
        val trimmed = raw.trim()
        if (!trimmed.lowercase().startsWith("upi://pay")) return null
        val query = trimmed.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        val pairs = query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            pair.substring(0, idx) to safeDecode(pair.substring(idx + 1))
        }
        // A key repeated, or disguised by encoding or spaces, could be read one way here and another
        // by the UPI app — and a merchant QR is handed over as scanned (UpiQr) — so the ledger would
        // record a payment other than the one made. Refuse such a QR (security review, 2026-11-14).
        if (pairs.any { (k, _) -> k.contains('%') || k != k.trim() }) return null
        val keys = pairs.map { it.first.lowercase() }
        if (PAYMENT_KEYS.any { key -> keys.count { it == key } > 1 }) return null
        val params = pairs.associate { (k, v) -> k.lowercase() to v }
        val vpa = params["pa"]?.takeIf { it.isNotBlank() } ?: return null
        return UpiPayee(
            vpa = vpa,
            name = params["pn"]?.takeIf { it.isNotBlank() },
            amountPaise = params["am"]?.let { Money.parseToPaise(it) },
            note = params["tn"]?.takeIf { it.isNotBlank() },
            raw = trimmed,
        )
    }

    private val PAYMENT_KEYS = listOf("pa", "pn", "am", "cu", "mc", "tr")

    /** Merchant QRs sometimes carry raw '%' — never let decoding crash a scan. */
    private fun safeDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
