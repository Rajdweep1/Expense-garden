package com.expensegarden.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCardArmingTest {
    // First tap at 10_000; the next pending card takes its place a frame or two later; Android
    // counts two taps up to 300 ms apart as one double tap.
    @Test fun `the second tap of a double tap does not answer the card that replaced the first`() =
        assertFalse(PendingCardArming.accepts(shownAtMs = 10_050, tapAtMs = 10_300))

    @Test fun `a card on screen long enough to read takes the tap`() =
        assertTrue(PendingCardArming.accepts(shownAtMs = 10_050, tapAtMs = 11_050))
}
