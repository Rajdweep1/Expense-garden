package com.expensegarden.app.game

import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.stats.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RareFoldTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    // Deliberately early in the month, with every fixture inside days 1..7. A sparse month
    // has long gaps between purchases, and a 7-day no-spend RUN would otherwise earn a rare on
    // its own and drown out the trigger each test is actually about.
    private val today: LocalDate = LocalDate.of(2026, 9, 8)

    private val categories = listOf(
        CategoryEntity(1, "Food & Drinks", null, false, updatedAt = 1L),
        CategoryEntity(103, "Chai & Snacks", 1, false, updatedAt = 1L),
        CategoryEntity(7, "Shopping", null, false, updatedAt = 1L),
        CategoryEntity(2, "Groceries", null, true, updatedAt = 1L),
        CategoryEntity(10, "Investments", null, true, updatedAt = 1L),
    )
    private val tree = CategoryTree(categories)
    private val golden = RareCatalog.byId("golden_tulip")!!

    private fun at(day: Int) =
        LocalDate.of(2026, 9, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun txn(
        uuid: String,
        categoryId: Long,
        day: Int = 10,
        regret: Regret = Regret.UNRATED,
        breached: Boolean = false,
    ) = TransactionEntity(
        uuid = uuid, amountPaise = 5_000, payeeId = 1, categoryId = categoryId,
        source = TxnSource.MANUAL, status = TxnStatus.LOGGED, regret = regret,
        breachedAtLogging = breached, note = null, occurredAt = at(day), createdAt = at(day),
        updatedAt = 1L,
    )

    // ---------- PlantMapper-level rules ----------

    @Test fun `a mapped plant carries its rare and keeps its own archetype`() {
        // The rare decorates; it never re-labels. Shopping grows a TULIP either way.
        val m = PlantMapper.map(txn("a", 7), tree, rare = golden)!!
        assertEquals(golden, m.rare)
        assertEquals(Archetype.TULIP, m.archetype)
    }

    @Test fun `a plant with no assignment has no rare`() {
        assertNull(PlantMapper.map(txn("a", 7), tree)!!.rare)
    }

    @Test fun `a regretted rare still becomes a zombie`() {
        // Spec §6: no exemptions. The garden's honesty outranks the collection's prettiness.
        val m = PlantMapper.map(txn("a", 7, regret = Regret.REGRET), tree, rare = golden)!!
        assertEquals(Archetype.ZOMBIE, m.archetype)
        assertNull(m.rare)
    }

    @Test fun `a breach purchase does not render as a rare`() {
        assertNull(PlantMapper.map(txn("a", 7, breached = true), tree, rare = golden)!!.rare)
    }

    @Test fun `a necessity keeps its own archetype even when it carries a rare`() {
        // Necessities are never shamed, so they can carry rares — but a Groceries purchase
        // must still render as a VEGETABLE_ROW, never as the rare's own family.
        val m = PlantMapper.map(txn("a", 2), tree, rare = golden)!!
        assertNotNull(m.rare)
        assertEquals(Archetype.VEGETABLE_ROW, m.archetype)
    }

    // ---------- fold-level wiring ----------

    private fun fold(txns: List<TransactionEntity>, signals: List<RareSignal>) =
        GardenFolder.foldAllTime(
            allTxns = txns,
            categories = categories,
            currentBudgets = emptyList(),
            currentMonthEvents = emptyList(),
            allTimeInvestmentCount = 0,
            today = today,
            zone = zone,
            rareSignals = signals,
        )

    private fun dodgesOn(day: Int) = List(3) { i ->
        RareSignal.GateDodged(eventId = (day * 10 + i).toLong(), atMillis = at(day))
    }

    @Test fun `no signals means no rare plants`() {
        val g = fold(listOf(txn("a", 7, day = 3)), emptyList())
        assertTrue(g.plants.all { it.rare == null })
    }

    @Test fun `three dodges then a purchase grows one rare`() {
        val g = fold(listOf(txn("later", 7, day = 5)), dodgesOn(2))
        assertEquals(1, g.plants.count { it.rare != null })
    }

    @Test fun `the rare grown always matches that purchase's own archetype`() {
        // THE honesty rule: a Groceries purchase must never come back as somebody's tulip.
        val g = fold(listOf(txn("groceries", 2, day = 5)), dodgesOn(2))
        val p = g.plants.single()
        assertEquals(Archetype.VEGETABLE_ROW, p.archetype)
        assertEquals(Archetype.VEGETABLE_ROW, p.rare?.baseArchetype)
    }

    @Test fun `a purchase made before the earn is not retroactively upgraded`() {
        val g = fold(listOf(txn("earlier", 7, day = 1)), dodgesOn(3))
        assertTrue(g.plants.all { it.rare == null })
    }

    @Test fun `only the first qualifying purchase takes the seed`() {
        val g = fold(listOf(txn("a", 7, day = 4), txn("b", 7, day = 5)), dodgesOn(2))
        assertEquals(1, g.plants.count { it.rare != null })
        assertEquals("a", g.plants.first { it.rare != null }.txnUuid)
    }

    @Test fun `a zombie purchase does not consume the seed`() {
        // The regret comes first chronologically but must be skipped, leaving the clean
        // purchase after it to take the reward.
        val g = fold(
            listOf(txn("zombie", 7, day = 4, regret = Regret.REGRET), txn("clean", 7, day = 5)),
            dodgesOn(2),
        )
        assertNull(g.plants.first { it.txnUuid == "zombie" }.rare)
        assertNotNull(g.plants.first { it.txnUuid == "clean" }.rare)
    }

    @Test fun `an investment does not consume the seed`() {
        // Investments are back-row trees, not bed plants — PlantMapper returns null for them,
        // so they never appear as candidates at all.
        val g = fold(listOf(txn("sip", 10, day = 4), txn("clean", 7, day = 5)), dodgesOn(2))
        assertNotNull(g.plants.first { it.txnUuid == "clean" }.rare)
    }

    @Test fun `the fold is deterministic across repeated calls`() {
        // Spec §4.1 — the property everything else rests on.
        val txns = listOf(txn("a", 7, day = 4), txn("b", 103, day = 6))
        val first = fold(txns, dodgesOn(2)).plants.map { it.txnUuid to it.rare?.id }
        val second = fold(txns, dodgesOn(2)).plants.map { it.txnUuid to it.rare?.id }
        assertEquals(first, second)
    }

    @Test fun `a landmark earn never becomes a plant`() {
        // House level 4 needs 12 tracked months; one month of txns cannot reach it, so drive
        // it directly through the override.
        val g = GardenFolder.foldAllTime(
            allTxns = listOf(txn("a", 7, day = 3)),
            categories = categories,
            currentBudgets = emptyList(),
            currentMonthEvents = emptyList(),
            allTimeInvestmentCount = 0,
            today = today,
            zone = zone,
            houseLevelOverride = 4,
            rareSignals = emptyList(),
        )
        assertTrue(g.plants.all { it.rare == null })
    }
}
