package com.expensegarden.app.core

import java.math.BigDecimal
import java.util.Locale

object Money {
    /** "450.50" -> 45050 paise. Null on garbage, zero, negative, or sub-paise precision. */
    fun parseToPaise(input: String): Long? {
        val value = input.trim().toBigDecimalOrNull() ?: return null
        if (value <= BigDecimal.ZERO) return null
        return try {
            value.movePointRight(2).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }

    fun display(paise: Long): String =
        String.format(Locale.US, "₹%d.%02d", paise / 100, paise % 100)

    /** NPCI intent `am` param format: strictly two decimals. */
    fun intentAmount(paise: Long): String =
        String.format(Locale.US, "%d.%02d", paise / 100, paise % 100)
}
