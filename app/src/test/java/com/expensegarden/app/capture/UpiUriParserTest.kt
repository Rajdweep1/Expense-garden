package com.expensegarden.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpiUriParserTest {
    @Test fun `parses full merchant qr`() {
        val p = UpiUriParser.parse("upi://pay?pa=chaiwala@ybl&pn=Sharma%20Chai&am=20.00&tn=chai")
        assertEquals("chaiwala@ybl", p?.vpa)
        assertEquals("Sharma Chai", p?.name)
        assertEquals(2000L, p?.amountPaise)
        assertEquals("chai", p?.note)
    }
    @Test fun `parses minimal qr with only vpa`() {
        val p = UpiUriParser.parse("upi://pay?pa=someone@oksbi")
        assertEquals("someone@oksbi", p?.vpa)
        assertNull(p?.name)
        assertNull(p?.amountPaise)
    }
    @Test fun `plus sign decodes as space in name`() {
        val p = UpiUriParser.parse("upi://pay?pa=x@upi&pn=Tea+Stall")
        assertEquals("Tea Stall", p?.name)
    }
    @Test fun `scheme is case insensitive`() {
        assertEquals("x@upi", UpiUriParser.parse("UPI://PAY?pa=x@upi")?.vpa)
    }
    @Test fun `rejects non-upi qr`() {
        assertNull(UpiUriParser.parse("https://example.com/pay?pa=x@upi"))
        assertNull(UpiUriParser.parse("hello world"))
    }
    @Test fun `rejects missing vpa`() {
        assertNull(UpiUriParser.parse("upi://pay?pn=NoVpa&am=10.00"))
    }
    @Test fun `garbage amount becomes null not crash`() {
        val p = UpiUriParser.parse("upi://pay?pa=x@upi&am=abc")
        assertEquals("x@upi", p?.vpa)
        assertNull(p?.amountPaise)
    }
    @Test fun `malformed percent encoding does not crash`() {
        val p = UpiUriParser.parse("upi://pay?pa=x@upi&pn=100%offer")
        assertEquals("x@upi", p?.vpa)
    }

    @Test fun `keeps the scanned string`() {
        val raw = "upi://pay?pa=shop@ybl&mc=5411&sign=ab+c%2B"
        assertEquals(raw, UpiUriParser.parse("  $raw\n")?.raw)
    }

    // A QR that repeats a payment key, or hides one behind encoding or spaces, could be read one way
    // here and another by the UPI app, so the ledger would record a payment other than the one made.
    // Such a QR is refused (security review, 2026-11-14).
    @Test fun `a QR repeating a payment key is refused`() {
        for (key in listOf("pa", "pn", "am", "cu", "mc", "tr"))
            assertNull(key, UpiUriParser.parse("upi://pay?pa=shop@ybl&$key=1&$key=2"))
    }

    @Test fun `a QR with an encoded or padded key is refused`() {
        assertNull(UpiUriParser.parse("upi://pay?pa=shop@ybl&%61m=9999"))
        assertNull(UpiUriParser.parse("upi://pay?pa=shop@ybl& am=5"))
        assertNull(UpiUriParser.parse("upi://pay?pa=shop@ybl&am =5"))
    }

    @Test fun `a repeated harmless key is still accepted`() =
        assertEquals("shop@ybl", UpiUriParser.parse("upi://pay?pa=shop@ybl&aid=1&aid=2")?.vpa)
}
