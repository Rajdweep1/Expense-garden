package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class BudgetInForceTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val oct = YearMonth.of(2026, 10)
    private fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
    private fun set(iso: String, paise: Long) = BudgetInForce.Change(at(iso), paise)
    private fun days(range: IntRange, paise: Long) = range.associateWith { paise }

    @Test fun `a carried budget is in force from the 1st`() =
        assertEquals(days(1..31, 1_500_000), BudgetInForce.overallByDay(oct, 1_500_000, emptyList(), null, zone))

    @Test fun `a budget first set on the 18th is in force from the 18th`() =
        assertEquals(
            days(18..31, 1_500_000),
            BudgetInForce.overallByDay(oct, null, listOf(set("2026-10-18T13:22", 1_500_000)), null, zone),
        )

    @Test fun `a raise counts from its own day, not before`() =
        assertEquals(
            days(1..27, 1_500_000) + days(28..31, 2_000_000),
            BudgetInForce.overallByDay(oct, 1_500_000, listOf(set("2026-10-28T21:00", 2_000_000)), null, zone),
        )

    @Test fun `a day's last change is the one in force that day`() =
        // Carried at breakfast on the 1st and raised before lunch: the 1st ran on the new amount.
        assertEquals(
            days(1..31, 2_000_000),
            BudgetInForce.overallByDay(oct, 1_500_000, listOf(set("2026-10-01T11:00", 2_000_000)), null, zone),
        )

    @Test fun `each set counts from its own day, whatever order they were recorded in`() =
        assertEquals(
            days(9..19, 1_200_000) + days(20..24, 1_000_000) + days(25..31, 1_400_000),
            BudgetInForce.overallByDay(
                oct, null,
                listOf(set("2026-10-20T10:00", 1_000_000), set("2026-10-09T10:00", 1_200_000), set("2026-10-25T10:00", 1_400_000)),
                null, zone,
            ),
        )

    @Test fun `a row older than budget events counts from its stamp`() =
        assertEquals(days(5..31, 900_000), BudgetInForce.overallByDay(oct, null, emptyList(), set("2026-10-05T09:00", 900_000), zone))

    @Test fun `a budget written after the month is in force on no day of it`() =
        assertEquals(emptyMap<Int, Long>(), BudgetInForce.overallByDay(oct, null, emptyList(), set("2026-11-02T09:00", 900_000), zone))

    @Test fun `a budget set before the month is in force all of it`() =
        assertEquals(
            days(1..31, 1_500_000),
            BudgetInForce.overallByDay(oct, null, listOf(set("2026-09-30T23:00", 1_500_000)), null, zone),
        )

    @Test fun `a month closes on the smallest budget in force during it`() =
        assertEquals(1_500_000L, BudgetInForce.closingPaise(days(1..27, 1_500_000) + days(28..31, 2_000_000)))

    @Test fun `a month without a budget from the 1st closes with none`() =
        assertNull(BudgetInForce.closingPaise(days(18..31, 1_500_000)))
}
