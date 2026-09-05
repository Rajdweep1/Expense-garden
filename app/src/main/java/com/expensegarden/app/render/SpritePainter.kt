package com.expensegarden.app.render

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.Plant

object SpriteNames {
    fun fileFor(archetype: Archetype, variant: Int = 0): String =
        archetype.name.lowercase() + "_" + variant + ".png"
}

object SpriteLoader {
    // 4A raised this from 4: rares live at variant indices above each archetype's ordinary
    // looks (see RareCatalog), and an index the loader never scans renders as procedural with
    // no error to point at.
    const val MAX_VARIANTS = 6

    /** Decode whatever is present in assets/garden/. Missing dir or files → empty/partial map. */
    fun load(context: Context): Map<Pair<Archetype, Int>, ImageBitmap> {
        val present = runCatching { context.assets.list("garden")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        return Archetype.entries.flatMap { arch ->
            (0 until MAX_VARIANTS).mapNotNull { v ->
                val name = SpriteNames.fileFor(arch, v)
                if (name !in present) null
                else runCatching {
                    context.assets.open("garden/$name").use { s ->
                        (arch to v) to BitmapFactory.decodeStream(s).asImageBitmap()
                    }
                }.getOrNull()
            }
        }.toMap()
    }

    /** Named non-plant structures (house levels). Same graceful partial-pack behavior. */
    fun loadStructures(context: Context): Map<String, ImageBitmap> {
        val present = runCatching { context.assets.list("garden")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        return present.filter { it.startsWith("house_") && it.endsWith(".png") }.mapNotNull { name ->
            runCatching {
                context.assets.open("garden/$name").use { s ->
                    name.removeSuffix(".png") to BitmapFactory.decodeStream(s).asImageBitmap()
                }
            }.getOrNull()
        }.toMap()
    }
}

/** Sprites where available, procedural everywhere else — a partial pack still renders a full garden.
 *  Unknown variants fall back to the archetype's base look before giving up on sprites entirely. */
class SpritePainter(
    private val sprites: Map<Pair<Archetype, Int>, ImageBitmap>,
    private val fallback: PlantPainter = ProceduralPainter(),
) : PlantPainter {
    override fun DrawScope.drawPlant(plant: Plant, anchor: Offset, heightPx: Float, swayDegrees: Float) {
        val bmp = sprites[plant.archetype to plant.variant] ?: sprites[plant.archetype to 0]
        if (bmp == null) {
            with(fallback) { drawPlant(plant, anchor, heightPx, swayDegrees) }
            return
        }
        val h = heightPx.toInt()
        val w = (heightPx * bmp.width / bmp.height).toInt()
        rotate(degrees = swayDegrees, pivot = anchor) {
            drawImage(
                image = bmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset((anchor.x - w / 2f).toInt(), (anchor.y - h).toInt()),
                dstSize = IntSize(w, h),
            )
        }
    }
}
