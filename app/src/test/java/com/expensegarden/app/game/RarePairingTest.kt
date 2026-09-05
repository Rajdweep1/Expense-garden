package com.expensegarden.app.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RarePairingTest {

    private fun earn(at: Long, key: String = "k$at", tier: RareTier = RareTier.UNCOMMON) =
        Earn(RareTrigger.STREAK_7, key, tier, sourceEventId = at, atMillis = at)

    private fun cand(uuid: String, at: Long, eligible: Boolean = true) =
        RarePairing.Candidate(uuid, at, eligible)

    @Test fun `an earn attaches to the next transaction after it`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("a", 50), cand("b", 150)))
        assertNull(m["a"])
        assertEquals("k100", m["b"]?.scopeKey)
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
        assertEquals("k100", m["a"]?.scopeKey)
        assertEquals("k200", m["b"]?.scopeKey)
    }

    @Test fun `an ineligible transaction is skipped and the seed waits`() {
        // A weed or zombie purchase must never consume a rare seed (spec §4) — otherwise a
        // breach would eat a reward you earned by restraint.
        val m = RarePairing.assign(
            listOf(earn(100)),
            listOf(cand("weed", 150, eligible = false), cand("ok", 200)),
        )
        assertNull(m["weed"])
        assertEquals("k100", m["ok"]?.scopeKey)
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
        assertEquals("plant", m["a"]?.scopeKey)
    }

    @Test fun `one transaction carries at most one rare`() {
        val m = RarePairing.assign(listOf(earn(100), earn(110)), listOf(cand("only", 150)))
        assertEquals(1, m.size)
        assertEquals("k100", m["only"]?.scopeKey)
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
        assertEquals("k100", m["early"]?.scopeKey)
        assertNull(m["late"])
    }

    @Test fun `earns are consumed oldest first`() {
        val m = RarePairing.assign(
            listOf(earn(200, "later"), earn(100, "earlier")),
            listOf(cand("a", 250), cand("b", 260)),
        )
        assertEquals("earlier", m["a"]?.scopeKey)
        assertEquals("later", m["b"]?.scopeKey)
    }

    @Test fun `transactions with identical timestamps are ordered by uuid for stability`() {
        val m = RarePairing.assign(listOf(earn(100)), listOf(cand("zeta", 150), cand("alpha", 150)))
        assertEquals("k100", m["alpha"]?.scopeKey)
        assertNull(m["zeta"])
    }
}
