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

    /** For humans: Indian digit grouping, and paise only when there are any.
     *
     *  Every amount in this app used to render through `Locale.US` as "₹33061.00" — neither
     *  how the number is grouped in the one country this app targets, nor a precision anyone
     *  wants on a rent payment. */
    fun display(paise: Long): String {
        val rupees = paise / 100
        val remainder = paise % 100
        val grouped = groupIndian(rupees)
        return if (remainder == 0L) "₹$grouped"
        else "₹$grouped." + remainder.toString().padStart(2, '0')
    }

    /** Lakh grouping: last three digits, then pairs. 1,65,305 rather than 165,305.
     *
     *  Written out rather than delegated to a formatter, and that is deliberate.
     *  `DecimalFormat` cannot express this at all — it supports one uniform grouping size and
     *  takes the interval nearest the decimal point, so the pattern "#,##,##0" yields 165,305.
     *  `NumberFormat.getInstance(en-IN)` does get it right, but through CLDR on the JVM and
     *  ICU on Android: two different implementations backing the unit tests and the app, which
     *  is the setup for a test that passes while the screen is wrong. Ten lines of arithmetic
     *  behave identically in both and cannot drift under a platform update. */
    private fun groupIndian(rupees: Long): String {
        val digits = rupees.toString()
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val out = StringBuilder()
        var i = head.length
        while (i > 2) {
            out.insert(0, "," + head.substring(i - 2, i))
            i -= 2
        }
        out.insert(0, head.substring(0, i))
        return "$out,${digits.takeLast(3)}"
    }

    /** NPCI intent `am` param format: strictly two decimals, no grouping.
     *
     *  Never route this through [display] — a grouped amount in a upi:// URI is a broken
     *  payment, not a cosmetic difference. */
    fun intentAmount(paise: Long): String =
        String.format(Locale.US, "%d.%02d", paise / 100, paise % 100)
}
