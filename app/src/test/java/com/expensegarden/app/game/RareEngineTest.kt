package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RareEngineTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private var nextId = 0L

    private fun monthMillis(month: Int, day: Int = 15): Long =
        LocalDate.of(2026, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun streak(days: Int, month: String = "2026-09") =
        RareSignal.StreakHit(++nextId, monthMillis(9), days, month)

    private fun dodge(month: Int = 9) = RareSignal.GateDodged(++nextId, monthMillis(month))

    private fun closed(month: String, spent: Long, budget: Long?) =
        RareSignal.MonthClosed(++nextId, monthMillis(9), month, spent, budget)

    private fun redeemed(uuid: String) = RareSignal.RegretCleared(++nextId, monthMillis(9), uuid)

    private fun earns(
        signals: List<RareSignal>,
        noSpendByMonth: Map<String, Int> = emptyMap(),
        breadthByMonth: Map<String, Int> = emptyMap(),
        houseLevel: Int = 1,
    ) = RareEngine.earns(signals, noSpendByMonth, breadthByMonth, houseLevel, zone)

    // ---------- individual triggers ----------

    @Test fun `a seven day streak earns an uncommon`() {
        val e = earns(listOf(streak(7)))
        assertEquals(1, e.size)
        assertEquals(RareTrigger.STREAK_7, e[0].trigger)
        assertEquals(RareTier.UNCOMMON, e[0].tier)
    }

    @Test fun `a thirty day streak earns a rare`() {
        assertEquals(RareTier.RARE, earns(listOf(streak(30))).single().tier)
    }

    @Test fun `streak thresholds that are not seven or thirty earn nothing`() {
        // Reconciler emits 3 and 14 as well; those are progress, not rewards.
        assertTrue(earns(listOf(streak(3), streak(14))).isEmpty())
    }

    @Test fun `three gate dodges in a month earn one uncommon`() {
        val e = earns(List(3) { dodge() })
        assertEquals(1, e.size)
        assertEquals(RareTrigger.GATE_DODGES, e.single().trigger)
    }

    @Test fun `two gate dodges earn nothing`() {
        assertTrue(earns(List(2) { dodge() }).isEmpty())
    }

    @Test fun `a month closed under budget earns a rare`() {
        val e = earns(listOf(closed("2026-08", spent = 5_000, budget = 10_000)))
        assertEquals(RareTier.RARE, e.single().tier)
        assertEquals(RareTrigger.MONTH_UNDER_BUDGET, e.single().trigger)
    }

    @Test fun `a month closed over budget earns nothing`() {
        assertTrue(earns(listOf(closed("2026-08", spent = 15_000, budget = 10_000))).isEmpty())
    }

    @Test fun `a month closed with no budget set earns nothing`() {
        // Under-spending a budget that does not exist is not an achievement, and treating a
        // null budget as zero would invert the comparison entirely.
        assertTrue(earns(listOf(closed("2026-08", spent = 15_000, budget = null))).isEmpty())
    }

    @Test fun `spending exactly the budget still counts as under`() {
        assertEquals(1, earns(listOf(closed("2026-08", spent = 10_000, budget = 10_000))).size)
    }

    @Test fun `seven no spend days in a month earn an uncommon`() {
        assertEquals(RareTrigger.NO_SPEND_DAYS, earns(emptyList(), noSpendByMonth = mapOf("2026-09" to 7)).single().trigger)
    }

    @Test fun `six no spend days earn nothing`() {
        assertTrue(earns(emptyList(), noSpendByMonth = mapOf("2026-09" to 6)).isEmpty())
    }

    @Test fun `eight root categories in a month earn a rare`() {
        val e = earns(emptyList(), breadthByMonth = mapOf("2026-09" to 8))
        assertEquals(RareTier.RARE, e.single().tier)
        assertEquals(RareTrigger.CATEGORY_BREADTH, e.single().trigger)
    }

    @Test fun `seven root categories earn nothing`() {
        assertTrue(earns(emptyList(), breadthByMonth = mapOf("2026-09" to 7)).isEmpty())
    }

    @Test fun `house level three and four each earn a landmark`() {
        val e = earns(emptyList(), houseLevel = 4)
        assertEquals(2, e.size)
        assertTrue(e.all { it.tier == RareTier.LANDMARK })
        assertEquals(setOf("house:3", "house:4"), e.map { it.scopeKey }.toSet())
    }

    @Test fun `house level two earns no landmark`() {
        assertTrue(earns(emptyList(), houseLevel = 2).isEmpty())
    }

    // ---------- the excluded trigger (spec 3.2) ----------

    @Test fun `abstaining from regret tagging earns nothing`() {
        // Regression for spec 3.2. Rewarding "a month with zero regrets" would reward NOT
        // tagging, which corrupts the ledger and violates "never punish the log". Only
        // redemption is ever rewarded — never abstention.
        assertTrue(earns(emptyList(), noSpendByMonth = emptyMap()).isEmpty())
    }

    @Test fun `redeeming a regret earns an uncommon`() {
        assertEquals(RareTrigger.REDEEMED, earns(listOf(redeemed("u1"))).single().trigger)
    }

    // ---------- anti-farming (spec 3.3) ----------

    @Test fun `re-tagging the same transaction repeatedly earns exactly one uncommon`() {
        // THE farming vector. LedgerRepository.setRegret no-ops only on an UNCHANGED value, so
        // REGRET -> WORTH_IT -> REGRET -> WORTH_IT emits regret_cleared twice. Two taps,
        // repeated, would otherwise mint Uncommons without limit.
        assertEquals(1, earns(listOf(redeemed("u1"), redeemed("u1"), redeemed("u1"))).size)
    }

    @Test fun `redeeming two different transactions earns two uncommons`() {
        assertEquals(2, earns(listOf(redeemed("u1"), redeemed("u2"))).size)
    }

    @Test fun `ten gate dodges in one month still earn only one uncommon`() {
        assertEquals(1, earns(List(10) { dodge() }).size)
    }

    @Test fun `gate dodges in two different months earn one each`() {
        assertEquals(2, earns(List(3) { dodge(9) } + List(3) { dodge(10) }).size)
    }

    @Test fun `a duplicated streak event earns only once`() {
        assertEquals(1, earns(listOf(streak(7), streak(7))).size)
    }

    @Test fun `the same month closed twice earns only once`() {
        val a = closed("2026-08", 1, 10)
        val b = closed("2026-08", 1, 10)
        assertEquals(1, earns(listOf(a, b)).size)
    }

    // ---------- determinism (spec 4.1) ----------

    @Test fun `the same signals yield identical earns on repeated folds`() {
        val signals = listOf(streak(7), closed("2026-08", 1, 10))
        val a = RareEngine.earns(signals, emptyMap(), emptyMap(), 1, zone)
        val b = RareEngine.earns(signals, emptyMap(), emptyMap(), 1, zone)
        assertEquals(a, b)
        assertEquals(a.map { it.species.id }, b.map { it.species.id })
    }

    @Test fun `input order does not change the result`() {
        // The fold receives whatever order Room returns; the island must not depend on it.
        val signals = listOf(streak(7), closed("2026-08", 1, 10), redeemed("u1"))
        assertEquals(
            RareEngine.earns(signals, emptyMap(), emptyMap(), 1, zone),
            RareEngine.earns(signals.reversed(), emptyMap(), emptyMap(), 1, zone),
        )
    }

    @Test fun `month derived earns are ordered by their month not by zero`() {
        // A rare earned for September's restraint must not sort ahead of an August event, or
        // the pairing would hand it to an earlier purchase than it should.
        val e = earns(emptyList(), noSpendByMonth = mapOf("2026-09" to 7, "2026-07" to 7))
        assertEquals(listOf("nospend:2026-07", "nospend:2026-09"), e.map { it.scopeKey })
    }
}
