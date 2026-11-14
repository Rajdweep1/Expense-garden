package com.expensegarden.app.capture

import com.expensegarden.app.core.Money

enum class QrKind { MERCHANT, PERSON }

/** What gets handed to the UPI app, decided from the scanned QR itself.
 *
 *  The app used to rebuild the link from four parsed fields, which dropped what a merchant QR
 *  carries (`mc`, `tr`, `sign`, …). And UPI apps refuse person-to-person payments started by
 *  another app whatever the link says: a friend's QR passed through unchanged still failed in
 *  super.money and GPay (phone E2E, 2026-11-14). So a merchant is paid with the QR as scanned,
 *  and a person is logged here and paid in the UPI app. */
object UpiQr {
    private const val PREFIX = "upi://pay"

    /** Merchant = an `mc` that is neither blank nor `0000` (his rule, 2026-11-14; not NPCI's). */
    fun kind(raw: String?): QrKind {
        val mc = raw?.let { param(it, "mc") }
        return if (!mc.isNullOrBlank() && mc != "0000") QrKind.MERCHANT else QrKind.PERSON
    }

    /** The QR's own amount, only when there is exactly one `am`, unpadded, that parses. Anything
     *  else is treated as absent and replaced by the typed amount: two `am`s would let the scan
     *  parser (last wins) and this (first wins) read different amounts (code review, 2026-11-14). */
    fun fixedAmountPaise(raw: String): Long? {
        val ams = params(raw, "am")
        val am = ams.singleOrNull()?.takeIf { it == it.trim() } ?: return null
        return Money.parseToPaise(am)
    }

    /** The one amount for the gate, the pending row and the link: a QR that sets one wins. */
    fun payableAmount(qrUri: String?, amountText: String): Long? =
        qrUri?.let(::fixedAmountPaise) ?: Money.parseUserAmount(amountText)

    /** The scanned string byte for byte — nothing decoded and re-encoded, so a `sign` survives —
     *  with the scheme lowercased (Android matches it case-sensitively) and, when the QR doesn't
     *  set a valid amount, its `am` replaced by [amountPaise] and `cu=INR` added if missing. */
    fun paymentUri(raw: String, amountPaise: Long): String {
        val trimmed = raw.trim()
        val lowered = if (trimmed.length >= PREFIX.length && trimmed.startsWith(PREFIX, ignoreCase = true))
            PREFIX + trimmed.substring(PREFIX.length) else trimmed
        if (fixedAmountPaise(lowered) != null) return lowered

        val hash = lowered.indexOf('#')
        val body = if (hash >= 0) lowered.substring(0, hash) else lowered
        val fragment = if (hash >= 0) lowered.substring(hash) else ""
        val q = body.indexOf('?')
        val head = if (q >= 0) body.substring(0, q + 1) else "$body?"
        val pairs = (if (q >= 0) body.substring(q + 1) else "").split('&')
            .filter { it.isNotEmpty() && keyOf(it) != "am" }
        val added = buildList {
            add("am=" + Money.intentAmount(amountPaise))
            if (pairs.none { keyOf(it) == "cu" }) add("cu=INR")
        }
        return head + (pairs + added).joinToString("&") + fragment
    }

    /** First value of [key] (case-insensitive), raw as scanned; null when absent. */
    private fun param(raw: String, key: String): String? = params(raw, key).firstOrNull()

    /** Every value of [key] (case-insensitive), raw as scanned, in order. */
    private fun params(raw: String, key: String): List<String> =
        raw.substringAfter('?', "").substringBefore('#').split('&')
            .filter { keyOf(it) == key }.map { it.substringAfter('=', "") }

    private fun keyOf(pair: String) = pair.substringBefore('=').lowercase()
}
