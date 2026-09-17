package com.expensegarden.app.gate

import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.PlantMapper
import com.expensegarden.app.stats.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePresentationTest {

    private fun view(
        severity: Severity,
        isNecessity: Boolean,
        streakDays: Int = 9,
        uuid: String = "fixed-uuid-for-tests",
    ) = GatePresentation.of(
        severity = severity,
        isNecessity = isNecessity,
        streakDays = streakDays,
        txnUuid = uuid,
        quip = "Budget says no. You say...?",
        scopeLabel = "Shopping",
        spentPaise = 400_000L,
        budgetPaise = 500_000L,
        candidatePaise = 224_000L,
        allowancePaise = 12_000L,
    )

    @Test
    fun `an OK verdict never shows a dialog`() {
        assertEquals(GateView.None, view(Severity.OK, isNecessity = false))
        assertEquals(GateView.None, view(Severity.OK, isNecessity = true))
    }

    @Test
    fun `a discretionary breach previews the weed`() {
        val v = view(Severity.BREACH, isNecessity = false)
        assertTrue(v is GateView.Weed)
        v as GateView.Weed
        assertTrue(v.archetype == Archetype.THISTLE_WEED || v.archetype == Archetype.ODD_MUSHROOM)
        assertEquals(0, v.variant)
    }

    @Test
    fun `the previewed weed is the one PlantMapper would actually grow`() {
        // Calls PlantMapper for real rather than re-deriving abs(seed) % 2 here. A copy of the
        // rule would agree with itself forever; this fails the moment the two drift, which is the
        // only thing standing between "promises a thistle" and "grows a mushroom".
        val shopping = listOf(CategoryEntity(7, "Shopping", null, false, updatedAt = 1L))
        val tree = CategoryTree(shopping)
        for (n in 1..40) {
            val uuid = "txn-$n"
            val txn = TransactionEntity(
                uuid = uuid, amountPaise = 5_000, payeeId = 1, categoryId = 7,
                source = TxnSource.QR_GATE, status = TxnStatus.LOGGED,
                breachedAtLogging = true, note = null,
                occurredAt = 1_760_000_000_000L, createdAt = 1L, updatedAt = 1L,
            )
            val grown = PlantMapper.map(txn, tree)!!
            val previewed = view(Severity.BREACH, isNecessity = false, uuid = uuid) as GateView.Weed
            assertEquals("uuid $uuid", grown.archetype, previewed.archetype)
            assertEquals("uuid $uuid", grown.variant, previewed.variant)
        }
    }

    @Test
    fun `a discretionary pace warning previews the streak`() {
        val v = view(Severity.PACE_WARNING, isNecessity = false)
        assertTrue(v is GateView.Streak)
        assertEquals(9, (v as GateView.Streak).days)
    }

    @Test
    fun `a necessity is neutral at both severities`() {
        assertTrue(view(Severity.BREACH, isNecessity = true) is GateView.Neutral)
        assertTrue(view(Severity.PACE_WARNING, isNecessity = true) is GateView.Neutral)
    }

    @Test
    fun `a necessity never carries a quip`() {
        // Spec section 10: necessities are off-limits, never mocked. Neutral has no quip field,
        // so this asserts on the data class's own toString - which names every field it has.
        // It fails the moment someone adds a quip to Neutral and populates it.
        val breach = view(Severity.BREACH, isNecessity = true)
        val pace = view(Severity.PACE_WARNING, isNecessity = true)
        assertTrue("a necessity view must not carry the quip", !breach.toString().contains("Budget says no"))
        assertTrue("a necessity view must not carry the quip", !pace.toString().contains("Budget says no"))
    }

    @Test
    fun `a necessity never offers a dodge`() {
        // GATE_DODGES earns a rare for backing out. Crediting a chemist run would make rares
        // farmable, against 4A's "earned by restraint, never by spending".
        assertEquals(false, view(Severity.BREACH, isNecessity = true).recordsDodge)
        assertEquals(false, view(Severity.PACE_WARNING, isNecessity = true).recordsDodge)
        assertEquals(true, view(Severity.BREACH, isNecessity = false).recordsDodge)
        assertEquals(true, view(Severity.PACE_WARNING, isNecessity = false).recordsDodge)
        assertEquals(false, GateView.None.recordsDodge)
    }

    @Test
    fun `the neutral card reports the total after this payment`() {
        val v = view(Severity.BREACH, isNecessity = true) as GateView.Neutral
        assertEquals(624_000L, v.afterPaise)      // 400000 spent + 224000 candidate
        assertEquals(500_000L, v.budgetPaise)
    }

    @Test
    fun `the weed card reports how far over budget this puts you`() {
        val v = view(Severity.BREACH, isNecessity = false) as GateView.Weed
        assertEquals(124_000L, v.overPaise)       // 624000 after - 500000 budget
    }

    @Test
    fun `the streak card reports the overshoot against today's allowance`() {
        val v = view(Severity.PACE_WARNING, isNecessity = false) as GateView.Streak
        assertEquals(612_000L, v.overPaise)       // 624000 after - 12000 allowance
        assertEquals(12_000L, v.allowancePaise)
    }
}
