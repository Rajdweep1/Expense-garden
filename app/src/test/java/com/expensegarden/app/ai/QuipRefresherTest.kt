package com.expensegarden.app.ai

import com.expensegarden.app.game.Tone
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuipRefresherTest {
    /** Records what would have been inserted, so the refresher is testable without Room. */
    private class Sink : QuipRefresher.Sink {
        val inserted = mutableListOf<Triple<Severity, Tone, String>>()
        var insertCalls = 0
        var stock: MutableMap<Pair<Severity, Tone>, Int> = mutableMapOf()
        var existing: MutableMap<Pair<Severity, Tone>, Set<String>> = mutableMapOf()
        override suspend fun unusedCount(severity: Severity, tone: Tone) = stock[severity to tone] ?: 0
        override suspend fun existingTexts(severity: Severity, tone: Tone) = existing[severity to tone] ?: emptySet()
        override suspend fun insert(severity: Severity, tone: Tone, lines: List<String>) {
            insertCalls++
            lines.forEach { inserted += Triple(severity, tone, it) }
        }
    }

    @Test fun `a full bucket is not refreshed at all`() = runBlocking {
        val sink = Sink().apply { stock[(Severity.BREACH to Tone.SHARP)] = 9 }
        val llm = FakeLlmClient()
        QuipRefresher(llm, sink).refresh(Tone.SHARP, severities = listOf(Severity.BREACH))
        assertTrue("no call should be made for a stocked bucket", llm.prompts.isEmpty())
        assertTrue(sink.inserted.isEmpty())
    }

    @Test fun `a depleted bucket asks for eight lines and inserts the clean ones`() = runBlocking {
        val sink = Sink().apply { stock[(Severity.BREACH to Tone.SHARP)] = 2 }
        val llm = FakeLlmClient()
        llm.enqueue(
            """
            1. Budget's gone. This is archaeology now.
            2. On your salary? Really?
            3. The compost heap has room.
            """.trimIndent()
        )
        QuipRefresher(llm, sink).refresh(Tone.SHARP, severities = listOf(Severity.BREACH))

        assertEquals(listOf(Persona.quipPrompt(Tone.SHARP, Severity.BREACH, 8)), llm.prompts)   // whole-prompt identity
        assertEquals(2, sink.inserted.size)                       // the salary line is rejected
        assertTrue(sink.inserted.none { it.third.contains("salary") })
        assertTrue(sink.inserted.all { it.second == Tone.SHARP })
    }

    @Test fun `a line the bucket already holds is not inserted again`() = runBlocking {
        // The prompt is identical every call and the model has no memory, so it regenerates
        // favourites; a duplicate row would be served back to back by the LRU picker.
        val sink = Sink().apply {
            stock[(Severity.BREACH to Tone.SHARP)] = 0
            existing[(Severity.BREACH to Tone.SHARP)] = setOf("Budget's gone. This is archaeology now.")
        }
        val llm = FakeLlmClient().apply { enqueue("Budget's gone. This is archaeology now.\nThe compost heap has room.") }
        QuipRefresher(llm, sink).refresh(Tone.SHARP, severities = listOf(Severity.BREACH))
        assertEquals(listOf("The compost heap has room."), sink.inserted.map { it.third })
    }

    @Test fun `stock at exactly the threshold is not refreshed, one below is`() = runBlocking {
        val sink = Sink().apply {
            stock[(Severity.BREACH to Tone.SHARP)] = QuipRefresher.LOW_STOCK
            stock[(Severity.PACE_WARNING to Tone.SHARP)] = QuipRefresher.LOW_STOCK - 1
        }
        val llm = FakeLlmClient().apply { enqueue("Pace, pace, pace.") }
        QuipRefresher(llm, sink).refresh(Tone.SHARP)                               // production default path
        assertEquals(listOf(Severity.PACE_WARNING), sink.inserted.map { it.first }.distinct())
    }

    @Test fun `the default path covers every severity the gate shows a line for`() = runBlocking {
        val sink = Sink()                                                            // all stock 0
        val llm = FakeLlmClient().apply { enqueue("one", "two") }
        QuipRefresher(llm, sink).refresh(Tone.SHARP)
        assertEquals(setOf(Severity.PACE_WARNING, Severity.BREACH), sink.inserted.map { it.first }.toSet())
    }

    @Test fun `a null on one severity does not skip the next`() = runBlocking {
        val sink = Sink()
        val llm = FakeLlmClient().apply { enqueue(null, "Still here.") }            // PACE_WARNING fails, BREACH answers
        QuipRefresher(llm, sink).refresh(Tone.SHARP)
        assertEquals(listOf(Severity.BREACH), sink.inserted.map { it.first })
    }

    @Test fun `an all-rejected response makes no insert call at all`() = runBlocking {
        val sink = Sink().apply { stock[(Severity.BREACH to Tone.SHARP)] = 0 }
        val llm = FakeLlmClient().apply { enqueue("On your salary? Really?") }
        QuipRefresher(llm, sink).refresh(Tone.SHARP, severities = listOf(Severity.BREACH))
        assertEquals(0, sink.insertCalls)
    }

    @Test fun `a null response inserts nothing and does not throw`() = runBlocking {
        val sink = Sink().apply { stock[(Severity.BREACH to Tone.SHARP)] = 0 }
        QuipRefresher(FakeLlmClient(), sink).refresh(Tone.SHARP, severities = listOf(Severity.BREACH))
        assertTrue(sink.inserted.isEmpty())
    }

    @Test fun `only the requested tone is topped up`() = runBlocking {
        val sink = Sink().apply {
            stock[(Severity.BREACH to Tone.GENTLE)] = 0
            stock[(Severity.BREACH to Tone.SAVAGE)] = 0
        }
        val llm = FakeLlmClient().apply { enqueue("Kindly, no.") }
        QuipRefresher(llm, sink).refresh(Tone.GENTLE, severities = listOf(Severity.BREACH))
        assertEquals(1, sink.inserted.size)
        assertEquals(Tone.GENTLE, sink.inserted[0].second)
    }
}
