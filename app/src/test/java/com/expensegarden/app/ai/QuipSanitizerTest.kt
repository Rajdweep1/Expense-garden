package com.expensegarden.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuipSanitizerTest {
    @Test fun `strips numbering and quotes`() {
        val out = QuipSanitizer.clean("1. \"That's a weed and you know it.\"")
        assertEquals("That's a weed and you know it.", out)
    }

    @Test fun `rejects an over-long line`() {
        assertEquals(null, QuipSanitizer.clean("x".repeat(200)))
    }

    @Test fun `rejects a blank line`() {
        assertEquals(null, QuipSanitizer.clean("   "))
    }

    @Test fun `rejects income references`() {
        assertEquals(null, QuipSanitizer.clean("On your salary? Bold."))
        assertEquals(null, QuipSanitizer.clean("That's a lot for someone earning what you earn."))
    }

    @Test fun `rejects comparisons to other people`() {
        assertEquals(null, QuipSanitizer.clean("Most people spend far less than this."))
    }

    @Test fun `rejects necessity shaming`() {
        assertEquals(null, QuipSanitizer.clean("Paying rent again? Predictable."))
        assertEquals(null, QuipSanitizer.clean("Groceries, seriously?"))
    }

    @Test fun `splits a multi-line response and drops only the bad lines`() {
        val raw = """
            1. Bold pace. The garden's getting thirsty.
            2. On your salary? Really?
            3. This one's fine. The next three are the problem.
        """.trimIndent()
        val out = QuipSanitizer.cleanAll(raw)
        assertEquals(2, out.size)
        assertTrue(out.none { it.contains("salary") })
    }
}
