package com.expensegarden.app.ai

import com.expensegarden.app.game.Tone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuipRefresherTest {
    /** Records what would have been inserted, so the planner is testable without Room. */
    private class Sink : QuipRefresher.Sink {
        val inserted = mutableListOf<Triple<String, Tone, String>>()
        var stock: MutableMap<Pair<String, Tone>, Int> = mutableMapOf()
        override suspend fun unusedCount(severity: String, tone: Tone) = stock[severity to tone] ?: 0
        override suspend fun insert(severity: String, tone: Tone, lines: List<String>) {
            lines.forEach { inserted += Triple(severity, tone, it) }
        }
    }

    @Test fun `a full bucket is not refreshed at all`() = runBlocking {
        val sink = Sink().apply { stock[("BREACH" to Tone.SHARP)] = 9 }
        val llm = FakeLlmClient()
        QuipRefresher(llm, sink).refresh(Tone.SHARP, severities = listOf("BREACH"))
        assertTrue("no call should be made for a stocked bucket", llm.prompts.isEmpty())
        assertTrue(sink.inserted.isEmpty())
    }

    @Test fun `a depleted bucket asks for eight lines and inserts the clean ones`() = runBlocking {
        val sink = Sink().apply { stock[("BREACH" to Tone.SHARP)] = 2 }
        val llm = FakeLlmClient()
        llm.enqueue(
            """
            1. Budget's gone. This is archaeology now.
            2. On your salary? Really?
            3. The compost heap has room.
            """.trimIndent()
        )
        QuipRefresher(llm, sink).refresh(Tone.SHARP, severities = listOf("BREACH"))

        assertEquals(1, llm.prompts.size)
        assertTrue("must ask for 8", llm.prompts[0].contains("exactly 8"))
        assertEquals(2, sink.inserted.size)                       // the salary line is rejected
        assertTrue(sink.inserted.none { it.third.contains("salary") })
        assertTrue(sink.inserted.all { it.second == Tone.SHARP })
    }

    @Test fun `a null response inserts nothing and does not throw`() = runBlocking {
        val sink = Sink().apply { stock[("BREACH" to Tone.SHARP)] = 0 }
        QuipRefresher(FakeLlmClient(), sink).refresh(Tone.SHARP, severities = listOf("BREACH"))
        assertTrue(sink.inserted.isEmpty())
    }

    @Test fun `only the requested tone is topped up`() = runBlocking {
        val sink = Sink().apply {
            stock[("BREACH" to Tone.GENTLE)] = 0
            stock[("BREACH" to Tone.SAVAGE)] = 0
        }
        val llm = FakeLlmClient().apply { enqueue("Kindly, no.") }
        QuipRefresher(llm, sink).refresh(Tone.GENTLE, severities = listOf("BREACH"))
        assertEquals(1, sink.inserted.size)
        assertEquals(Tone.GENTLE, sink.inserted[0].second)
    }
}
