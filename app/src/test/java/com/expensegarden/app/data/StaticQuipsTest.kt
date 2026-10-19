package com.expensegarden.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StaticQuipsTest {
    @Test fun `each severity keeps five static lines`() {
        // The phone script's rotation check (Step 7) counts on five per severity.
        assertEquals(mapOf("PACE_WARNING" to 5, "BREACH" to 5), STATIC_QUIPS.groupingBy { it.first }.eachCount())
    }

    @Test fun `no static line claims what the gate cannot know`() {
        // Quips are picked by severity alone, so a line may only say what is true of every
        // payment at that severity: BREACH includes the one that crosses the line, and a pace
        // warning can fire on the 1st.
        val forbidden = listOf("already gone", "archaeology", "This one's fine", "It's not the 1st")
        for ((_, text) in STATIC_QUIPS) for (claim in forbidden) {
            assertFalse("'$text' claims '$claim'", text.contains(claim))
        }
    }
}
