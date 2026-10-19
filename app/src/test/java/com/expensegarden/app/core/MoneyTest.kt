package com.expensegarden.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun `parses plain rupees`() = assertEquals(45000L, Money.parseToPaise("450"))
    @Test fun `parses rupees with paise`() = assertEquals(45050L, Money.parseToPaise("450.50"))
    @Test fun `parses single decimal digit`() = assertEquals(45050L, Money.parseToPaise("450.5"))
    @Test fun `rejects garbage`() = assertNull(Money.parseToPaise("45a"))
    @Test fun `rejects zero and negative`() {
        assertNull(Money.parseToPaise("0"))
        assertNull(Money.parseToPaise("-5"))
    }
    @Test fun `rejects sub-paise precision`() = assertNull(Money.parseToPaise("450.505"))
    // ---------- display formatting (review fix, 2026-09-06) ----------

    @Test fun `display keeps paise when they are non-zero`() =
        assertEquals("₹450.50", Money.display(45050L))

    @Test fun `display uses Indian digit grouping`() {
        // Lakh grouping is 2-2-3, not thousands. Locale.US rendered these as 165305.00 and
        // 33061.00 — neither how the number is grouped here nor a precision anyone wants.
        assertEquals("₹1,65,305", Money.display(1_65_305_00L))
        assertEquals("₹33,061", Money.display(33_061_00L))
        assertEquals("₹9,000", Money.display(9_000_00L))
        assertEquals("₹450", Money.display(450_00L))
    }

    @Test fun `display drops paise on whole rupees`() = assertEquals("₹450", Money.display(45_000L))

    @Test fun `display handles zero and sub-rupee amounts`() {
        assertEquals("₹0", Money.display(0L))
        assertEquals("₹0.05", Money.display(5L))
        assertEquals("₹29", Money.display(2_900L))
    }

    @Test fun `display groups a crore without losing a pair`() =
        assertEquals("₹1,00,00,000", Money.display(1_00_00_000_00L))

    @Test fun `display groups every boundary from hundreds to lakhs`() {
        assertEquals("₹999", Money.display(999_00L))
        assertEquals("₹1,000", Money.display(1_000_00L))
        assertEquals("₹99,999", Money.display(99_999_00L))
        assertEquals("₹1,00,000", Money.display(1_00_000_00L))
    }

    @Test fun `intentAmount stays ungrouped with two decimals — NPCI requires it`() {
        // Load-bearing: this string goes into the upi:// URI. A grouped amount is a broken
        // payment, so it must never be routed through display().
        assertEquals("165305.00", Money.intentAmount(1_65_305_00L))
        assertEquals("0.05", Money.intentAmount(5L))
    }
    @Test fun `formats intent amount`() = assertEquals("450.50", Money.intentAmount(45050L))
    @Test fun `formats intent amount whole`() = assertEquals("450.00", Money.intentAmount(45000L))

    // ---------- typed amounts (review fix, 2026-10-11) ----------

    @Test fun `typed amounts may carry grouping, spaces and a rupee sign`() {
        assertEquals(1_000_000L, Money.parseUserAmount("10,000"))
        assertEquals(10_000_000L, Money.parseUserAmount("1,00,000"))
        assertEquals(1_000_000L, Money.parseUserAmount("₹10,000"))
        assertEquals(1_000_000L, Money.parseUserAmount(" 10 000 "))
        assertEquals(1_200_050L, Money.parseUserAmount("12,000.50"))
    }

    @Test fun `typed amounts are still strict once the decoration is gone`() {
        assertNull(Money.parseUserAmount(""))
        assertNull(Money.parseUserAmount("abc"))
        assertNull(Money.parseUserAmount("0"))
        assertNull(Money.parseUserAmount("-5"))
        assertNull(Money.parseUserAmount("1,000.505"))
        // The input that deleted a budget on 2026-10-11. It must be a value, never "nothing".
        assertEquals(1_200_000L, Money.parseUserAmount("12,000"))
    }

    @Test fun `an amount field shows whole rupees without paise`() {
        assertEquals("10000", Money.inputText(1_000_000L))
        assertEquals("12000.50", Money.inputText(1_200_050L))
        assertEquals("12000.05", Money.inputText(1_200_005L))
    }

    @Test fun `displayRupees rounds to whole rupees with Indian grouping`() {
        assertEquals("₹1,234", Money.displayRupees(123_388))
        assertEquals("₹10,016", Money.displayRupees(1_001_612))
        assertEquals("₹1,50,000", Money.displayRupees(15_000_000))
        assertEquals("₹1", Money.displayRupees(50))
        assertEquals("₹0", Money.displayRupees(49))
    }
}
