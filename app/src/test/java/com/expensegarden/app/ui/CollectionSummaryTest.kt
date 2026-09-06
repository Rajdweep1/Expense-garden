package com.expensegarden.app.ui

import com.expensegarden.app.game.RareTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionSummaryTest {

    @Test fun `a tier heading carries its own progress`() {
        assertEquals("Uncommon — 2 of 8", CollectionSummary.heading(RareTier.UNCOMMON, 2, 8))
    }

    @Test fun `every tier name is title cased`() {
        assertEquals("Landmark — 0 of 2", CollectionSummary.heading(RareTier.LANDMARK, 0, 2))
        assertEquals("Rare — 6 of 6", CollectionSummary.heading(RareTier.RARE, 6, 6))
    }

    @Test fun `the hidden line counts what is left`() {
        assertEquals("6 still hidden", CollectionSummary.hiddenLine(2, 8))
    }

    @Test fun `a fully collected tier has no hidden line`() {
        // The whole point of the change: no row that says nothing.
        assertNull(CollectionSummary.hiddenLine(8, 8))
    }

    @Test fun `an untouched tier reports its whole pool`() {
        assertEquals("8 still hidden", CollectionSummary.hiddenLine(0, 8))
    }

    @Test fun `one remaining reads naturally`() {
        assertEquals("1 still hidden", CollectionSummary.hiddenLine(7, 8))
    }

    @Test fun `more found than the pool holds never goes negative`() {
        // Guards a retired species: foundBy is keyed by id and outlives the catalogue, so a
        // removed entry would leave found > total and print "-1 still hidden".
        assertNull(CollectionSummary.hiddenLine(9, 8))
        assertEquals("Rare — 9 of 8", CollectionSummary.heading(RareTier.RARE, 9, 8))
    }
}
