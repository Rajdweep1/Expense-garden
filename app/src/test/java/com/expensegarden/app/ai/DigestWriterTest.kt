package com.expensegarden.app.ai

import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.Tone
import com.expensegarden.app.game.Trigger
import com.expensegarden.app.game.Weather
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestWriterTest {
    private val facts = PromptFacts(
        weather = Weather.OVERCAST, houseLevel = 2, streakDays = 5, spentPaise = 1_234_500L,
        budgetPaise = 2_000_000L, regretCount = 1, dodgeCount = 2, monthKey = "2026-09",
        topCategories = listOf("Groceries"),
    )
    private fun daily(vararg t: Trigger) = DigestReason(DigestKind.DAILY, "2026-09-05", t.toList())

    @Test fun `strips quotes and fences from the completion`() = runBlocking {
        val llm = FakeLlmClient().apply { enqueue("```\n\"A quiet day pulled you back under pace.\"\n```") }
        assertEquals(
            "A quiet day pulled you back under pace.",
            DigestWriter(llm).compose(daily(Trigger.GateDodged(1)), facts, Tone.SHARP),
        )
    }

    @Test fun `a language-tagged fence is stripped too`() = runBlocking {
        val llm = FakeLlmClient().apply { enqueue("```text\nThe drought lifted.\n```") }
        assertEquals("The drought lifted.", DigestWriter(llm).compose(daily(Trigger.GateDodged(1)), facts, Tone.SHARP))
    }

    @Test fun `a person attack nulls the whole digest`() = runBlocking {
        // Spec §11: nothing is written, and the all-or-nothing job retries next open.
        val llm = FakeLlmClient().apply { enqueue("Nice restraint. Though on your salary, that was overdue.") }
        assertNull(DigestWriter(llm).compose(daily(Trigger.GateDodged(1)), facts, Tone.SAVAGE))
    }

    @Test fun `a neutral necessity mention is allowed in a digest`() = runBlocking {
        val llm = FakeLlmClient().apply { enqueue("Groceries were steady and rent went out on time.") }
        assertEquals(
            "Groceries were steady and rent went out on time.",
            DigestWriter(llm).compose(daily(Trigger.MonthClosed("2026-08")), facts, Tone.GENTLE),
        )
    }

    @Test fun `a null completion is a null digest`() = runBlocking {
        assertNull(DigestWriter(FakeLlmClient()).compose(daily(Trigger.GateDodged(1)), facts, Tone.SHARP))
    }

    @Test fun `the prompt carries the boundaries, the facts, and every trigger`() = runBlocking {
        val llm = FakeLlmClient().apply { enqueue("ok") }
        DigestWriter(llm).compose(
            daily(Trigger.WeatherChanged(Weather.OVERCAST, Weather.SUNNY), Trigger.GateDodged(2), Trigger.StreakHit(7)),
            facts, Tone.SHARP,
        )
        val p = llm.prompts.single()
        for (clause in Persona.BOUNDARIES) assertTrue("missing boundary: $clause", p.contains(clause))
        assertTrue(p.contains("Weather: OVERCAST"))                                   // facts.render()
        assertTrue(p.contains("never say or imply that they spent differently"))      // spec §5 prohibition
        assertTrue(p.contains("This is a WIN"))                                      // spec §5 gate.dodged
        assertTrue(p.contains("7-day streak"))
    }

    @Test fun `a monthly reason asks for a look back, a daily one for the day`() = runBlocking {
        val llm = FakeLlmClient().apply { enqueue("a", "b") }
        val w = DigestWriter(llm)
        w.compose(DigestReason(DigestKind.MONTHLY, "2026-08", listOf(Trigger.MonthClosed("2026-08"))), facts, Tone.SHARP)
        w.compose(daily(Trigger.GateDodged(1)), facts, Tone.SHARP)
        assertTrue(llm.prompts[0].contains("has just closed"))
        assertTrue(llm.prompts[1].contains("two or three sentences"))
    }
}
