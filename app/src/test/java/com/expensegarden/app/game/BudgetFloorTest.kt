package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class BudgetFloorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val oct = YearMonth.of(2026, 10)
    private fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    @Test fun `a carried month counts from the 1st`() =
        assertEquals(1, BudgetFloor.overallFromDay(oct, true, listOf(at("2026-10-18T13:22")), null, zone))

    @Test fun `a budget first set on the 18th counts from the 18th`() =
        assertEquals(18, BudgetFloor.overallFromDay(oct, false, listOf(at("2026-10-18T13:22")), null, zone))

    @Test fun `the earliest of several sets wins`() =
        assertEquals(9, BudgetFloor.overallFromDay(
            oct, false, listOf(at("2026-10-20T10:00"), at("2026-10-09T10:00"), at("2026-10-25T10:00")), null, zone,
        ))

    @Test fun `a row older than budget events counts from its stamp`() =
        assertEquals(5, BudgetFloor.overallFromDay(oct, false, emptyList(), at("2026-10-05T09:00"), zone))

    @Test fun `a budget written after the month counts no day of it`() =
        assertEquals(32, BudgetFloor.overallFromDay(oct, false, emptyList(), at("2026-11-02T09:00"), zone))

    @Test fun `a stamp before the month counts the whole month`() =
        assertEquals(1, BudgetFloor.overallFromDay(oct, false, listOf(at("2026-09-30T23:00")), null, zone))
}
