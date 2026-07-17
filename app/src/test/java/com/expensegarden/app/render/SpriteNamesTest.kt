package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import org.junit.Assert.assertEquals
import org.junit.Test

class SpriteNamesTest {
    @Test fun `file names are lowercase archetype names with a variant suffix`() {
        assertEquals("petal_flower_0.png", SpriteNames.fileFor(Archetype.PETAL_FLOWER))
        assertEquals("petal_flower_2.png", SpriteNames.fileFor(Archetype.PETAL_FLOWER, 2))
        assertEquals("odd_mushroom_0.png", SpriteNames.fileFor(Archetype.ODD_MUSHROOM))
        assertEquals(
            Archetype.entries.size,
            Archetype.entries.map { SpriteNames.fileFor(it) }.toSet().size,
        )
    }
}
