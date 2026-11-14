package com.expensegarden.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AwaitingSelectTest {
    private fun p(id: String, scannedAt: Long, handedOffAt: Long?) =
        AwaitingPayment(id, "x@ybl", "X", "upi://pay?pa=x@ybl", null, scannedAt, handedOffAt, 0)

    @Test fun `the oldest payment just handed off opens on return`() {
        val list = listOf(p("b", 20, 21), p("a", 10, 11), p("c", 30, null))
        assertEquals("a", AwaitingSelect.toOpen(list)?.id)
        assertNull(AwaitingSelect.toOpen(listOf(p("c", 30, null))))
    }

    @Test fun `home cards are every awaiting payment except the open one, oldest first`() =
        assertEquals(listOf("a", "c"), AwaitingSelect.homeCards(listOf(p("c", 30, null), p("b", 20, 21), p("a", 10, null)), "b").map { it.id })
}
