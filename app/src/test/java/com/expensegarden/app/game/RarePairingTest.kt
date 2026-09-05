package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RarePairingTest {

    private fun earn(at: Long, key: String = "k$at", tier: RareTier = RareTier.UNCOMMON) =
        Earn(RareTrigger.STREAK_7, key, tier, sourceEventId = at, atMillis = at)

    // TULIP has rares in both plantable tiers, so it is the archetype that can always carry one.
    private fun cand(
        uuid: String,
        at: Long,
        eligible: Boolean = true,
        archetype: Archetype = Archetype.TULIP,
    ) = RarePairing.Candidate(uuid, at, archetype, eligible)

    @Test fun `an earn attaches to the next transaction after it`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("a", 50), cand("b", 150)))
        assertNull(m["a"])
        assertNotNull(m["b"])
    }

    @Test fun `an earn with no later transaction stays pending`() {
        assertTrue(RarePairing.assign(listOf(earn(100)), listOf(cand("a", 50))).isEmpty())
    }

    @Test fun `no earns means no assignments`() {
        assertTrue(RarePairing.assign(emptyList(), listOf(cand("a", 50))).isEmpty())
    }

    @Test fun `two earns attach to two different transactions`() {
        val m = RarePairing.assign(
            listOf(earn(100), earn(200)),
            listOf(cand("a", 150), cand("b", 250)),
        )
        assertNotNull(m["a"])
        assertNotNull(m["b"])
    }

    @Test fun `an ineligible transaction is skipped and the seed waits`() {
        // A weed or zombie purchase must never consume a rare seed (spec §4) — otherwise a
        // breach would eat a reward you earned by restraint.
        val m = RarePairing.assign(
            listOf(earn(100)),
            listOf(cand("weed", 150, eligible = false), cand("ok", 200)),
        )
        assertNull(m["weed"])
        assertNotNull(m["ok"])
    }

    @Test fun `all-ineligible transactions leave the seed pending`() {
        val m = RarePairing.assign(
            listOf(earn(100)),
            listOf(cand("w1", 150, eligible = false), cand("w2", 200, eligible = false)),
        )
        assertTrue(m.isEmpty())
    }

    @Test fun `landmarks are never paired to a transaction`() {
        // Landmarks are island features, not plants (spec §8) — album only until 4B.
        val m = RarePairing.assign(listOf(earn(100, tier = RareTier.LANDMARK)), listOf(cand("a", 150)))
        assertTrue(m.isEmpty())
    }

    @Test fun `a landmark does not block a plantable earn behind it`() {
        val m = RarePairing.assign(
            listOf(earn(100, "land", RareTier.LANDMARK), earn(110, "plant")),
            listOf(cand("a", 150)),
        )
        assertNotNull(m["a"])
    }

    @Test fun `one transaction carries at most one rare`() {
        val m = RarePairing.assign(listOf(earn(100), earn(110)), listOf(cand("only", 150)))
        assertEquals(1, m.size)
        assertNotNull(m["only"])
    }

    @Test fun `a transaction at exactly the earn instant does not take it`() {
        // Strictly after. Equal timestamps are common — the reconciler stamps a whole batch
        // with one currentTimeMillis() — so this boundary is hit in practice, not in theory.
        assertTrue(RarePairing.assign(listOf(earn(100)), listOf(cand("a", 100))).isEmpty())
    }

    @Test fun `pairing is stable across repeated calls`() {
        val earns = listOf(earn(100), earn(200))
        val cands = listOf(cand("a", 150), cand("b", 250))
        assertEquals(RarePairing.assign(earns, cands), RarePairing.assign(earns, cands))
    }

    @Test fun `transactions are consumed in occurredAt order regardless of input order`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("late", 300), cand("early", 150)))
        assertNotNull(m["early"])
        assertNull(m["late"])
    }

    @Test fun `earns are consumed oldest first`() {
        val m = RarePairing.assign(
            listOf(earn(200, "later"), earn(100, "earlier")),
            listOf(cand("a", 250), cand("b", 260)),
        )
        assertNotNull(m["a"])
        assertNotNull(m["b"])
        assertEquals(2, m.size)
    }

    @Test fun `transactions with identical timestamps are ordered by uuid for stability`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("zeta", 150), cand("alpha", 150)))
        assertNotNull(m["alpha"])
        assertNull(m["zeta"])
    }

    @Test fun `a seed declines a purchase whose archetype has no rare form`() {
        // THE honesty rule. ZOMBIE has no rare form, and neither does any archetype the
        // catalogue does not cover — the seed waits rather than turning that purchase into
        // an unrelated species and making the garden misreport what was bought.
        val m = RarePairing.assign(
            listOf(earn(100)),
            listOf(cand("nomatch", 150, archetype = Archetype.TREE), cand("tulip", 200)),
        )
        assertNull(m["nomatch"])
        assertNotNull(m["tulip"])
    }

    @Test fun `an award records which earn produced it`() {
        // The album shows HOW a species was earned; that only works if the pairing keeps them
        // together rather than deriving them separately.
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("a", 150)))
        assertEquals("k100", m["a"]?.earn?.scopeKey)
        assertNotNull(m["a"]?.species)
    }

    @Test fun `an assigned species always matches the transaction's own archetype`() {
        val m = RarePairing.assign(
            listOf(earn(100)),
            listOf(cand("a", 150, archetype = Archetype.HEDGE)),
        )
        assertEquals(Archetype.HEDGE, m["a"]?.species?.baseArchetype)
    }
}
