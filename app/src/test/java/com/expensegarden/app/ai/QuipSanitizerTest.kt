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

    @Test fun `a forbidden word inside a longer word is not a match`() {
        // "rent" as a bare substring fires on current/different/apparently/inherently —
        // words a model writing budget copy uses constantly. Rejecting those would starve
        // the refresh while looking like it worked.
        assertEquals("Your current pace is ambitious.", QuipSanitizer.clean("Your current pace is ambitious."))
        assertEquals("That's a different kind of weed.", QuipSanitizer.clean("That's a different kind of weed."))
        assertEquals("Apparently the budget disagrees.", QuipSanitizer.clean("Apparently the budget disagrees."))
    }

    @Test fun `the necessity rules still bite at a word start, plurals included`() {
        assertEquals(null, QuipSanitizer.clean("Rent again? Predictable."))
        assertEquals(null, QuipSanitizer.clean("Two doctors in one month."))
        assertEquals(null, QuipSanitizer.clean("Still affording that, are you?"))
    }

    @Test fun `the digest check catches person attacks across lines`() {
        assertTrue(QuipSanitizer.attacksThePerson("Solid week.\nThough on your salary, maybe ease off."))
        assertTrue(QuipSanitizer.attacksThePerson("Most people spend far less than this."))
    }

    @Test fun `the digest check lets a neutral necessity mention through`() {
        // A month recap that names groceries or rent is doing its job — only the quip rule
        // treats a bare mention as mockery.
        assertEquals(false, QuipSanitizer.attacksThePerson("Groceries were steady. Rent went out on the 3rd."))
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
