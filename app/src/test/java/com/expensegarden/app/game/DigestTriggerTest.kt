package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DigestTriggerTest {
    private val today = LocalDate.of(2026, 9, 5)
    private val month = MonthFacts("2026-09", regretCount = 0)

    private fun state(
        weather: Weather = Weather.SUNNY,
        houseLevel: Int = 2,
        streakDays: Int = 4,
    ) = GardenState(
        monthKey = "2026-09", weather = weather, plants = emptyList(), spentPaise = 0L,
        backRowTreeCount = 0, trunkTier = 0, butterflies = 0, streakDays = streakDays,
        noSpendDays = 0, archived = false, gridRows = 5, gridCols = 5, houseLevel = houseLevel,
    )

    private fun snap(
        weather: Weather = Weather.SUNNY,
        houseLevel: Int = 2,
        streakDays: Int = 4,
        lastEventId: Long = 100L,
    ) = DigestSnapshot(weather, houseLevel, streakDays, lastEventId)

    private fun evaluate(
        last: DigestSnapshot? = snap(),
        events: List<DigestEvent> = emptyList(),
        monthFacts: MonthFacts = month,
        now: GardenState = state(),
        mutedUntilMillis: Long? = null,
    ) = DigestTrigger.evaluate(last, events, monthFacts, now, today, mutedUntilMillis, nowMillis = 5_000L)

    // ---------- silence ----------

    @Test fun `an ordinary day says nothing`() {
        assertTrue(evaluate().isSilent)
    }

    // ---------- comparison triggers ----------

    @Test fun `weather change speaks`() {
        val v = evaluate(last = snap(weather = Weather.SUNNY), now = state(weather = Weather.DROUGHT))
        assertEquals(
            listOf(Trigger.WeatherChanged(Weather.SUNNY, Weather.DROUGHT)),
            v.daily?.triggers,
        )
        assertEquals("2026-09-05", v.daily?.scopeKey)
    }

    @Test fun `house level up speaks`() {
        val v = evaluate(last = snap(houseLevel = 2), now = state(houseLevel = 3))
        assertEquals(listOf(Trigger.HouseLevelled(2, 3)), v.daily?.triggers)
    }

    @Test fun `a house level going DOWN says nothing`() {
        // foldAllTime(houseLevelOverride) can produce this; a silent no-op beats a fabricated demotion.
        assertTrue(evaluate(last = snap(houseLevel = 3), now = state(houseLevel = 2)).isSilent)
    }

    // ---------- event triggers ----------

    @Test fun `gate dodge is reported with its count`() {
        val v = evaluate(events = listOf(DigestEvent.GateDodged(101), DigestEvent.GateDodged(102)))
        assertEquals(listOf(Trigger.GateDodged(2)), v.daily?.triggers)
    }

    @Test fun `crossing several streak thresholds at once reports only the highest`() {
        // Reconciler.decide emits one streak.hit per threshold crossed, so a single open can
        // deliver 3, 7, 14 and 30 together. That is one achievement, not four.
        val v = evaluate(
            events = listOf(
                DigestEvent.StreakHit(101, 3), DigestEvent.StreakHit(102, 7),
                DigestEvent.StreakHit(103, 14), DigestEvent.StreakHit(104, 30),
            )
        )
        assertEquals(listOf(Trigger.StreakHit(30)), v.daily?.triggers)
    }

    @Test fun `the first regret of the month speaks`() {
        val v = evaluate(
            events = listOf(DigestEvent.Regretted(101)),
            monthFacts = MonthFacts("2026-09", regretCount = 1),
        )
        assertEquals(listOf(Trigger.FirstRegretOfMonth), v.daily?.triggers)
    }

    @Test fun `a later regret in the same month stays silent`() {
        // regretCount 3 with 1 in the window means 2 came before it — not the first.
        val v = evaluate(
            events = listOf(DigestEvent.Regretted(101)),
            monthFacts = MonthFacts("2026-09", regretCount = 3),
        )
        assertTrue(v.isSilent)
    }

    @Test fun `the second regret of the month is not the first`() {
        // regretCount 2 with 1 in the window means exactly one came before it. Boundary pin:
        // mutating `<= 0` to `<= 1` would announce this one as the first.
        val v = evaluate(
            events = listOf(DigestEvent.Regretted(101)),
            monthFacts = MonthFacts("2026-09", regretCount = 2),
        )
        assertTrue(v.isSilent)
    }

    // ---------- plural month close ----------

    @Test fun `four closed months produce four monthly reasons`() {
        // Fed out of order, with one month duplicated: the sort and the distinct both earn
        // their lines. game_event has no (type, month) uniqueness, so a duplicate is possible.
        val v = evaluate(
            events = listOf(
                DigestEvent.MonthClosed(101, "2026-07"), DigestEvent.MonthClosed(102, "2026-05"),
                DigestEvent.MonthClosed(103, "2026-08"), DigestEvent.MonthClosed(104, "2026-06"),
                DigestEvent.MonthClosed(105, "2026-07"),
            )
        )
        assertEquals(
            listOf("2026-05", "2026-06", "2026-07", "2026-08"),
            v.monthly.map { it.scopeKey },
        )
        assertTrue("monthly must all be MONTHLY", v.monthly.all { it.kind == DigestKind.MONTHLY })
        assertEquals(
            listOf(Trigger.MonthClosed("2026-05")),
            v.monthly[0].triggers,                                  // reasonJson must not be empty
        )
    }

    @Test fun `a closed month does not suppress the daily card`() {
        // runReconciler emits month.closed and streak.hit in ONE call. Suppressing DAILY here
        // would advance the watermark past the dodge and lose it forever. Both cards write;
        // they live on different screens.
        val v = evaluate(
            events = listOf(DigestEvent.MonthClosed(101, "2026-08"), DigestEvent.GateDodged(102)),
            now = state(weather = Weather.DROUGHT),
        )
        assertEquals(1, v.monthly.size)
        assertEquals(
            listOf(Trigger.WeatherChanged(Weather.SUNNY, Weather.DROUGHT), Trigger.GateDodged(1)),
            v.daily?.triggers,
        )
    }

    // ---------- first run ----------

    @Test fun `first run stays silent on comparison triggers`() {
        // No baseline exists, so "the weather changed" would be a fabrication.
        val v = evaluate(last = null, now = state(weather = Weather.DROUGHT, houseLevel = 4))
        assertTrue(v.isSilent)
    }

    @Test fun `first run still speaks on a self-contained event`() {
        val v = evaluate(last = null, events = listOf(DigestEvent.GateDodged(1)))
        assertEquals(listOf(Trigger.GateDodged(1)), v.daily?.triggers)
    }

    // ---------- mute ----------

    @Test fun `a live mute silences everything`() {
        val v = evaluate(
            events = listOf(DigestEvent.MonthClosed(101, "2026-08")),
            now = state(weather = Weather.DROUGHT),
            mutedUntilMillis = 9_000L,          // nowMillis is 5_000 — still muted
        )
        assertTrue(v.isSilent)
    }

    @Test fun `the mute boundary is exclusive`() {
        // nowMillis == mutedUntilMillis is NOT muted: the window is [start, until).
        val v = evaluate(events = listOf(DigestEvent.GateDodged(101)), mutedUntilMillis = 5_000L)
        assertFalse(v.isSilent)
    }

    @Test fun `an expired mute does not silence`() {
        val v = evaluate(events = listOf(DigestEvent.GateDodged(101)), mutedUntilMillis = 1_000L)
        assertFalse(v.isSilent)
    }

    // ---------- combination ----------

    @Test fun `several daily triggers fold into one card`() {
        val v = evaluate(
            last = snap(weather = Weather.SUNNY),
            events = listOf(DigestEvent.GateDodged(101)),
            now = state(weather = Weather.OVERCAST),
        )
        assertTrue("a daily card must not carry monthly reasons", v.monthly.isEmpty())
        assertEquals(DigestKind.DAILY, v.daily?.kind)
        // Comparison triggers precede event triggers — the writer may come to rely on it.
        assertEquals(
            listOf(Trigger.WeatherChanged(Weather.SUNNY, Weather.OVERCAST), Trigger.GateDodged(1)),
            v.daily?.triggers,
        )
    }
}
