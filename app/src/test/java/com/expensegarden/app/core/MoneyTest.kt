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
    @Test fun `formats display rupees`() = assertEquals("₹450.50", Money.display(45050L))
    @Test fun `formats intent amount`() = assertEquals("450.50", Money.intentAmount(45050L))
    @Test fun `formats intent amount whole`() = assertEquals("450.00", Money.intentAmount(45000L))
}
