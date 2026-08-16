package com.expensegarden.app.game

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GardenFolderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val categories = listOf(
        CategoryEntity(1, "Food & Drinks", null, false),
        CategoryEntity(103, "Chai & Snacks", 1, false),
        CategoryEntity(2, "Groceries", null, true),
        CategoryEntity(10, "Investments", null, true),
    )
    private fun at(day: Int, month: Int = 7) = LocalDate.of(2026, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private var n = 0
    private fun txn(cat: Long, day: Int, paise: Long = 5_000, breached: Boolean = false, regret: Regret = Regret.UNRATED, month: Int = 7) =
        TransactionEntity(
            uuid = "u${n++}", amountPaise = paise, payeeId = 1, categoryId = cat,
            source = TxnSource.MANUAL, status = TxnStatus.LOGGED, breachedAtLogging = breached,
            regret = regret, note = null, occurredAt = at(day, month), createdAt = at(day, month),
        )
    private fun dodge(day: Int) = GameEventEntity(
        id = n++.toLong(), type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = at(day),
    )

    private fun fold(
        txns: List<TransactionEntity>,
        budgets: List<BudgetEntity> = emptyList(),
        events: List<GameEventEntity> = emptyList(),
        allTimeInvestmentCount: Int = 0,
        today: LocalDate = LocalDate.of(2026, 7, 10),
    ) = GardenFolder.fold("2026-07", txns, categories, budgets, events, allTimeInvestmentCount, today, zone)

    @Test fun `every logged txn plants in chronological order`() {
        val g = fold(listOf(txn(103, day = 3), txn(2, day = 1), txn(103, day = 2)))
        assertEquals(3, g.plants.size)
        assertEquals(Tile(0, 0), g.plants.first { it.archetype == Archetype.HEDGE }.tile)  // day-1 groceries planted first
        assertEquals(15_000L, g.spentPaise)
    }

    @Test fun `weather follows overall severity`() {
        val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 100_000))
        assertEquals(Weather.SUNNY, fold(listOf(txn(103, 2, paise = 1_000)), budget).weather)
        // day 10/31 allowance = 1000*10/31*1.15 = ₹370.96; spend ₹500 → OVERCAST
        assertEquals(Weather.OVERCAST, fold(listOf(txn(103, 2, paise = 50_000)), budget).weather)
        assertEquals(Weather.DROUGHT, fold(listOf(txn(103, 2, paise = 150_000)), budget).weather)
        assertEquals(Weather.SUNNY, fold(listOf(txn(103, 2, paise = 1_000)), emptyList()).weather)  // no budget = sunny
    }

    @Test fun `regret flip re-folds a flower into a zombie and back`() {
        val flower = fold(listOf(txn(103, 2)))
        assertTrue(flower.plants.single().archetype != Archetype.ZOMBIE)
        val zombie = fold(listOf(txn(103, 2, regret = Regret.REGRET)))
        assertEquals(Archetype.ZOMBIE, zombie.plants.single().archetype)
        assertTrue(!zombie.plants.single().isWeed)
    }

    @Test fun `investments feed the back row not the plot`() {
        val g = fold(listOf(txn(10, 2, paise = 500_000)), allTimeInvestmentCount = 12)
        assertEquals(0, g.plants.size)
        assertEquals(2, g.backRowTreeCount)      // 10..24 SIPs → 2 trees
        assertEquals(12, g.trunkTier)
    }

    @Test fun `butterflies count dodges capped at five`() {
        val g = fold(emptyList(), events = (1..9).map { dodge(it) })
        assertEquals(5, g.butterflies)
    }

    @Test fun `archived month freezes at final day state`() {
        val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 100_000))
        val g = GardenFolder.fold(
            "2026-07", listOf(txn(103, 2, paise = 150_000)), categories, budget, emptyList(),
            allTimeInvestmentCount = 0, today = LocalDate.of(2026, 8, 15), zone = zone,
        )
        assertTrue(g.archived)
        assertEquals(Weather.DROUGHT, g.weather)
    }

    @Test fun `identical inputs fold to identical state`() {
        val txns = listOf(txn(103, 2), txn(2, 3))
        assertEquals(fold(txns), fold(txns))
    }

    // ---- 1C.5: the persistent all-time island ----

    private fun foldAll(
        txns: List<TransactionEntity>,
        budgets: List<BudgetEntity> = emptyList(),
        events: List<GameEventEntity> = emptyList(),
        allTimeInvestmentCount: Int = 0,
        today: LocalDate = LocalDate.of(2026, 7, 10),
        houseLevelOverride: Int? = null,
    ) = GardenFolder.foldAllTime(txns, categories, budgets, events, allTimeInvestmentCount, today, zone, houseLevelOverride)

    @Test fun `all-time fold plants every month on one island in chronological order`() {
        val g = foldAll(listOf(txn(103, day = 5, month = 7), txn(2, day = 2, month = 5), txn(103, day = 10, month = 6)))
        assertEquals(3, g.plants.size)
        assertEquals(Tile(0, 0), g.plants.first { it.archetype == Archetype.HEDGE }.tile)   // May groceries planted first
        assertEquals("2026-07", g.monthKey)
        assertEquals(4, g.gridRows)                                                          // min rows still applies
        assertTrue(!g.archived)
    }

    @Test fun `month markers sit at each month's first plant and skip investment-only months`() {
        val g = foldAll(listOf(
            txn(2, day = 1, month = 5), txn(103, day = 9, month = 5),   // May: plants 0,1
            txn(10, day = 3, month = 6, paise = 200_000),               // June: investment only — no plant
            txn(103, day = 4, month = 7),                               // July: plant 2
        ))
        assertEquals(
            listOf(MonthMarker("2026-05", Tile(0, 0)), MonthMarker("2026-07", Tile(0, 2))),
            g.monthMarkers,
        )
    }

    @Test fun `all-time stats come from the current month only`() {
        val budget = listOf(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 100_000))
        val g = foldAll(listOf(txn(103, day = 2, month = 5, paise = 900_000), txn(103, day = 2, month = 7, paise = 1_000)), budget)
        assertEquals(Weather.SUNNY, g.weather)          // May's blowout must not drought today's sky
        assertEquals(1_000L, g.spentPaise)              // the strip stays a monthly figure
        assertEquals(2, g.plants.size)                  // but every txn still stands in the field
    }

    @Test fun `grid grows in square rings with total plant count`() {
        val txns = (1..12).map { txn(103, day = it, month = 5) } + (1..11).map { txn(103, day = it, month = 7) }
        val g = foldAll(txns)
        assertEquals(6, g.gridRows)                     // 23 plants → 2 rings → side 6
        assertEquals(6, g.gridCols)
        assertTrue(g.plants.none { it.tile in SpiralTiler.houseTiles(6) })
    }

    @Test fun `house levels up with months tracked, investment-only months count`() {
        fun monthsSpan(months: List<Int>) = months.map { m -> txn(103, day = 2, month = m) }
        assertEquals(1, foldAll(monthsSpan(listOf(7))).houseLevel)
        assertEquals(1, foldAll(emptyList()).houseLevel)                      // day-0 hut
        assertEquals(2, foldAll(monthsSpan(listOf(4, 5, 7))).houseLevel)      // 3 months → cottage
        val withInvestmentMonth = monthsSpan(listOf(4, 5)) + txn(10, day = 3, month = 6, paise = 200_000)
        assertEquals(2, foldAll(withInvestmentMonth).houseLevel)              // 3 tracked, one by SIP alone
    }

    @Test fun `monthly greenhouse fold keeps serpentine and default house level`() {
        val g = fold((1..23).map { txn(103, day = it) })
        assertEquals(5, g.gridRows)                     // ceil(23/5) — postcards unchanged
        assertEquals(1, g.houseLevel)
    }

    // ---- 1C.7: the growing homestead footprint ----

    @Test fun `houseLevelOverride yields the old level and the old footprint together`() {
        // The "before" state must be internally coherent — old level AND old footprint — not a
        // hybrid, or the expansion tween lerps against a bogus layout.
        val txns = (1..7).map { m -> txn(103, day = 2, month = m) }            // 7 months → level 3
        val now = foldAll(txns)
        assertEquals(3, now.houseLevel)
        assertEquals(SpiralTiler.gridSide(now.plants.size, 3), now.gridRows)

        val before = foldAll(txns, houseLevelOverride = 2)
        assertEquals(2, before.houseLevel)
        assertEquals(SpiralTiler.gridSide(before.plants.size, 2), before.gridRows)
        // Same transactions, same order — only the tiles differ.
        assertEquals(now.plants.map { it.txnUuid }, before.plants.map { it.txnUuid })
        assertNotEquals(now.plants.map { it.tile }, before.plants.map { it.tile })
    }

    @Test fun `default fold is unchanged by the override parameter`() {
        val txns = (4..6).map { m -> txn(103, day = 2, month = m) }
        assertEquals(
            foldAll(txns).plants.map { it.tile },
            foldAll(txns, houseLevelOverride = null).plants.map { it.tile },
        )
    }
}
