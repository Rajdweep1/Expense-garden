package com.expensegarden.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiQrTest {
    // The hand-off link: the scanned QR byte for byte, the scheme lowercased (Android matches it
    // case-sensitively), and an `am` kept only when the QR fixes a valid one — the UPI app asks for
    // the amount otherwise (pay-first spec, D10).
    @Test fun `a QR without an amount is handed over unchanged`() {
        val raw = "upi://pay?pa=shop@ybl&pn=Sharma%20Chai&mc=5411&tr=ORD1&sign=ab+c%2B%3D"
        assertEquals(raw, UpiQr.handoffUri(raw))
        assertEquals("upi://pay?pa=x@ybl&", UpiQr.handoffUri("upi://pay?pa=x@ybl&"))
    }

    @Test fun `a QR with its own amount keeps it`() {
        val raw = "upi://pay?pa=shop@ybl&mc=5411&am=50.00&cu=INR"
        assertEquals(raw, UpiQr.handoffUri(raw))
    }

    @Test fun `a malformed or duplicated am is stripped and nothing is added`() {
        assertEquals("upi://pay?pa=shop@ybl&mc=5411", UpiQr.handoffUri("upi://pay?pa=shop@ybl&am=0.00&mc=5411"))
        assertEquals("upi://pay?pa=s@ybl&mc=5411", UpiQr.handoffUri("upi://pay?pa=s@ybl&mc=5411&am=50&am=60"))
        assertEquals("upi://pay?pa=x@ybl&mc=5411#x", UpiQr.handoffUri("upi://pay?pa=x@ybl&am=&mc=5411#x"))
    }

    @Test fun `an uppercase scheme is lowercased and nothing else`() =
        assertEquals("upi://pay?pa=X@ybl&mc=5411", UpiQr.handoffUri("UPI://PAY?pa=X@ybl&mc=5411"))

    @Test fun `keys are read in any case`() {
        assertEquals(QrKind.MERCHANT, UpiQr.kind("upi://pay?pa=shop@ybl&MC=5411"))
        assertEquals(500L, UpiQr.fixedAmountPaise("upi://pay?pa=shop@ybl&AM=5"))
    }

    @Test fun `merchant means an mc that is neither blank nor 0000`() {
        assertEquals(QrKind.MERCHANT, UpiQr.kind("upi://pay?pa=s@ybl&mc=5411"))
        for (raw in listOf(
            "upi://pay?pa=s@ybl&mc=0000", "upi://pay?pa=s@ybl&mc=", "upi://pay?pa=s@ybl",
            "upi://pay?pa=f@okaxis&pn=Friend&aid=uGICAgI",
        )) assertEquals(raw, QrKind.PERSON, UpiQr.kind(raw))
        assertEquals(QrKind.PERSON, UpiQr.kind(null))
    }

    @Test fun `duplicate mc - the first one wins`() {
        assertEquals(QrKind.PERSON, UpiQr.kind("upi://pay?pa=s@ybl&mc=0000&mc=5411"))
        assertEquals(QrKind.MERCHANT, UpiQr.kind("upi://pay?pa=s@ybl&mc=5411&mc=0000"))
    }

    @Test fun `fixedAmountPaise reads only a valid am`() {
        assertEquals(5000L, UpiQr.fixedAmountPaise("upi://pay?pa=s@ybl&am=50.00"))
        assertEquals(700L, UpiQr.fixedAmountPaise("upi://pay?pa=s@ybl&am=7"))
        for (bad in listOf("", "&am=abc", "&am=", "&am=0.00", "&am=50.555"))
            assertNull(bad, UpiQr.fixedAmountPaise("upi://pay?pa=s@ybl$bad"))
    }

    @Test fun `payableAmount - a valid QR amount wins over the typed one`() {
        assertEquals(5000L, UpiQr.payableAmount("upi://pay?pa=s@ybl&am=50", "999"))
        assertEquals(120000L, UpiQr.payableAmount("upi://pay?pa=s@ybl&am=0.00", "1,200"))
        assertEquals(120000L, UpiQr.payableAmount(null, "1,200"))
        assertNull(UpiQr.payableAmount(null, "abc"))
        assertFalse(UpiQr.payableAmount("upi://pay?pa=s@ybl", "") != null)
    }

    // Two `am`s, or one with spaces: the scan parser and UpiQr would read different amounts, so
    // neither counts as fixed and the typed amount replaces them all (code review, 2026-11-14).
    @Test fun `a duplicated or padded am does not fix the amount`() {
        assertNull(UpiQr.fixedAmountPaise("upi://pay?pa=s@ybl&mc=5411&am=50&am=60"))
        assertNull(UpiQr.fixedAmountPaise("upi://pay?pa=s@ybl&mc=5411&am= 50"))
    }
}
