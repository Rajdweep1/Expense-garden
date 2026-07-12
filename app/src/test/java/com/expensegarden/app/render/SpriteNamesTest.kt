package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import org.junit.Assert.assertEquals
import org.junit.Test

class SpriteNamesTest {
    @Test fun `file names are the lowercase archetype names`() {
        assertEquals("petal_flower.png", SpriteNames.fileFor(Archetype.PETAL_FLOWER))
        assertEquals("odd_mushroom.png", SpriteNames.fileFor(Archetype.ODD_MUSHROOM))
        assertEquals(Archetype.entries.size, Archetype.entries.map { SpriteNames.fileFor(it) }.toSet().size)
    }
}
