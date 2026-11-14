package com.expensegarden.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiQrTest {
    @Test fun `a merchant QR passes through with only the amount added`() {
        val raw = "upi://pay?pa=shop@ybl&pn=Sharma%20Chai&mc=5411&tr=ORD1&sign=ab%2Bc%3D"
        assertEquals("$raw&am=1.00&cu=INR", UpiQr.paymentUri(raw, 100))
    }

    @Test fun `a QR with its own amount is passed through untouched`() {
        val raw = "upi://pay?pa=shop@ybl&mc=5411&am=50.00&cu=INR"
        assertEquals(raw, UpiQr.paymentUri(raw, 5000))
    }

    @Test fun `cu is not added twice`() {
        val raw = "upi://pay?pa=shop@ybl&mc=5411&cu=INR"
        assertEquals("$raw&am=1.00", UpiQr.paymentUri(raw, 100))
    }

    @Test fun `keys match in any case`() {
        val raw = "upi://pay?pa=shop@ybl&AM=5&MC=5411"
        assertEquals(raw, UpiQr.paymentUri(raw, 500))
        assertEquals(QrKind.MERCHANT, UpiQr.kind(raw))
    }

    @Test fun `an invalid am is replaced by the typed amount`() {
        val out = UpiQr.paymentUri("upi://pay?pa=shop@ybl&am=0.00&mc=5411", 100)
        assertEquals("upi://pay?pa=shop@ybl&mc=5411&am=1.00&cu=INR", out)
        assertEquals("upi://pay?pa=x&am=1.00&cu=INR", UpiQr.paymentUri("upi://pay?pa=x&am=", 100))
    }

    @Test fun `an uppercase scheme is lowercased and nothing else`() {
        assertTrue(UpiQr.paymentUri("UPI://PAY?pa=X@ybl&mc=5411", 100).startsWith("upi://pay?pa=X@ybl&mc=5411"))
    }

    @Test fun `a literal plus and percent-escapes survive`() {
        val raw = "upi://pay?pa=shop@ybl&mc=5411&sign=ab+c%2B%3D"
        assertEquals("$raw&am=1.00&cu=INR", UpiQr.paymentUri(raw, 100))
    }

    @Test fun `a bare QR gets the amount and currency`() =
        assertEquals("upi://pay?pa=x@ybl&am=1.00&cu=INR", UpiQr.paymentUri("upi://pay?pa=x@ybl", 100))

    @Test fun `a trailing ampersand adds no second one`() =
        assertEquals("upi://pay?pa=x@ybl&am=1.00&cu=INR", UpiQr.paymentUri("upi://pay?pa=x@ybl&", 100))

    @Test fun `a fragment stays last`() =
        assertEquals(
            "upi://pay?pa=x@ybl&mc=5411&am=1.00&cu=INR#x",
            UpiQr.paymentUri("upi://pay?pa=x@ybl&mc=5411#x", 100),
        )

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
        assertEquals(
            "upi://pay?pa=s@ybl&mc=5411&am=1.00&cu=INR",
            UpiQr.paymentUri("upi://pay?pa=s@ybl&mc=5411&am=50&am=60", 100),
        )
    }
}
