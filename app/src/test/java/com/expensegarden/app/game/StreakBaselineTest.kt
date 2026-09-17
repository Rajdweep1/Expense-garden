package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class StreakBaselineTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val september = YearMonth.of(2026, 9)

    private fun millisOf(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `a fresh install mid-month starts at that day`() {
        val installed = millisOf(2026, 9, 10)
        assertEquals(10, StreakBaseline.firstObservedDay(installed, null, september, zone))
    }

    @Test
    fun `an install before this month covers the whole month`() {
        val installed = millisOf(2026, 8, 3)
        assertEquals(1, StreakBaseline.firstObservedDay(installed, null, september, zone))
    }

    @Test
    fun `a restored ledger older than this install wins`() {
        // New phone installed on the 25th, but the restored history reaches back to July.
        val installed = millisOf(2026, 9, 25)
        val earliestEvent = millisOf(2026, 7, 4)
        assertEquals(1, StreakBaseline.firstObservedDay(installed, earliestEvent, september, zone))
    }

    @Test
    fun `the earlier of the two always wins within the month`() {
        val installed = millisOf(2026, 9, 12)
        val earliestEvent = millisOf(2026, 9, 5)
        assertEquals(5, StreakBaseline.firstObservedDay(installed, earliestEvent, september, zone))
    }

    @Test
    fun `an unrecorded install falls back to the ledger alone`() {
        val earliestEvent = millisOf(2026, 9, 7)
        assertEquals(7, StreakBaseline.firstObservedDay(0L, earliestEvent, september, zone))
    }

    @Test
    fun `knowing nothing at all covers the whole month`() {
        // Neither recorded: assume the month is fully observed rather than inventing a floor.
        assertEquals(1, StreakBaseline.firstObservedDay(0L, null, september, zone))
    }
}
