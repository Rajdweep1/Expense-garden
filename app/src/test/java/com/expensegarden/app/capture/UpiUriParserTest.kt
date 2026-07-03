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
}
