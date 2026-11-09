package com.expensegarden.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCheckTest {
    @Test fun `a key check tells rejected from unreachable`() {
        assertEquals(KeyCheck.WORKS, KeyCheck.classify(200, null))
        assertEquals(KeyCheck.REJECTED, KeyCheck.classify(400, null))   // Gemini answers a bad key with 400 API_KEY_INVALID
        assertEquals(KeyCheck.REJECTED, KeyCheck.classify(401, null))
        assertEquals(KeyCheck.REJECTED, KeyCheck.classify(403, null))
        assertEquals(KeyCheck.UNREACHABLE, KeyCheck.classify(500, null))
        assertEquals(KeyCheck.UNREACHABLE, KeyCheck.classify(null, java.net.UnknownHostException()))
    }
}
