package com.expensegarden.app.render

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.game.RareCatalog
import com.expensegarden.app.game.RareTier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the one 4A failure mode with no error attached to it.
 *
 *  A rare is a variant index on its own archetype, so `SpritePainter` finds it through
 *  `SpriteLoader`'s `(archetype, variant)` map. If a catalogue entry names a variant the loader
 *  never scans — because `MAX_VARIANTS` is too low — or the asset file name does not match, the
 *  plant silently falls back to procedural art. Nothing throws, nothing logs, and the reward the
 *  user earned simply looks like an ordinary plant.
 *
 *  Sprites are generated over time, so this asserts on what is actually shipped in the APK
 *  rather than demanding the full set — a missing file is a to-do, a MIS-KEYED file is a bug. */
@RunWith(AndroidJUnit4::class)
class RareSpriteLoadTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun shippedAssets(): Set<String> =
        runCatching { context.assets.list("garden")?.toSet() ?: emptySet() }.getOrDefault(emptySet())

    @Test fun every_shipped_rare_sprite_is_reachable_through_the_loader() {
        val present = shippedAssets()
        val loaded = SpriteLoader.load(context)

        for (species in RareCatalog.all()) {
            val archetype = species.baseArchetype ?: continue     // landmarks are not plants yet
            val file = "${species.spriteName}.png"
            if (file !in present) continue                        // not generated yet
            assertTrue(
                "asset $file ships but SpriteLoader has no entry for " +
                    "(${archetype.name}, ${species.variant}) — it would render procedurally " +
                    "with no error. Check SpriteLoader.MAX_VARIANTS.",
                loaded.containsKey(archetype to species.variant),
            )
        }
    }

    @Test fun the_loader_scan_range_covers_every_catalogued_rare() {
        // Independent of what has been generated: a catalogue entry above the scan range could
        // never load, whenever its art arrives.
        for (species in RareCatalog.all()) {
            if (species.baseArchetype == null) continue
            assertTrue(
                "${species.id} uses variant ${species.variant}, outside the loader's " +
                    "0 until ${SpriteLoader.MAX_VARIANTS} scan",
                species.variant < SpriteLoader.MAX_VARIANTS,
            )
        }
    }

    @Test fun every_shipped_landmark_sprite_is_reachable_through_the_structure_map() {
        // A landmark has no archetype, so it cannot key into SpriteLoader's (archetype,
        // variant) map — it loads by id through loadStructures instead. A mis-keyed landmark
        // fails exactly as silently as a mis-keyed rare: nothing throws, nothing logs, and the
        // reward the user waited a year for simply is not on the island.
        val present = shippedAssets()
        val structures = SpriteLoader.loadStructures(context)
        for (species in RareCatalog.pool(RareTier.LANDMARK)) {
            val file = "${species.spriteName}.png"
            if (file !in present) continue                 // not generated yet
            assertTrue(
                "asset $file ships but loadStructures has no entry for id '${species.id}'",
                structures.containsKey(species.id),
            )
        }
    }

    @Test fun widening_the_structure_map_did_not_drop_the_house_sprites() {
        // The house ladder shares this map. Losing house_0 would render the homestead blank.
        val structures = SpriteLoader.loadStructures(context)
        val present = shippedAssets()
        for (i in 0..3) {
            if ("house_$i.png" !in present) continue
            assertTrue("house_$i vanished from loadStructures", structures.containsKey("house_$i"))
        }
    }

    @Test fun a_generated_rare_does_not_collide_with_an_ordinary_variant() {
        // If a rare shared a variant index with an ordinary look, the ordinary plant would start
        // rendering the reward art for free.
        val loaded = SpriteLoader.load(context)
        for (species in RareCatalog.pool(RareTier.UNCOMMON) + RareCatalog.pool(RareTier.RARE)) {
            val archetype = species.baseArchetype ?: continue
            if (!loaded.containsKey(archetype to species.variant)) continue
            val ordinary = com.expensegarden.app.game.PlantMapper.variantCount(archetype)
            assertTrue(
                "${species.id} sits at variant ${species.variant} but ${archetype.name} rolls " +
                    "0..${ordinary - 1} by chance",
                species.variant >= ordinary,
            )
        }
    }
}
