package com.expensegarden.app.capture

import com.expensegarden.app.core.Money
import java.net.URLDecoder

data class UpiPayee(
    val vpa: String,
    val name: String?,
    val amountPaise: Long?,
    val note: String?,
)

object UpiUriParser {
    /** Returns null when [raw] is not a upi://pay URI with a payee address. */
    fun parse(raw: String): UpiPayee? {
        val trimmed = raw.trim()
        if (!trimmed.lowercase().startsWith("upi://pay")) return null
        val query = trimmed.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = pair.substring(0, idx).lowercase()
            val value = safeDecode(pair.substring(idx + 1))
            key to value
        }.toMap()
        val vpa = params["pa"]?.takeIf { it.isNotBlank() } ?: return null
        return UpiPayee(
            vpa = vpa,
            name = params["pn"]?.takeIf { it.isNotBlank() },
            amountPaise = params["am"]?.let { Money.parseToPaise(it) },
            note = params["tn"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Merchant QRs sometimes carry raw '%' — never let decoding crash a scan. */
    private fun safeDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }
}
