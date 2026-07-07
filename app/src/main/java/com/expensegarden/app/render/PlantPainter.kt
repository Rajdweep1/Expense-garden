package com.expensegarden.app.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SizeTier

/** The seam: mechanics/motion never know how flora is drawn.
 *  anchor = bottom-center of the plant on its tile; heightPx already includes tier scaling;
 *  swayDegrees is applied around the anchor so wind reads the same for sprites and vectors. */
interface PlantPainter {
    fun DrawScope.drawPlant(plant: Plant, anchor: Offset, heightPx: Float, swayDegrees: Float)
}

class ProceduralPainter : PlantPainter {
    override fun DrawScope.drawPlant(plant: Plant, anchor: Offset, heightPx: Float, swayDegrees: Float) {
        rotate(degrees = swayDegrees, pivot = anchor) {
            val h = heightPx
            val jitter = 0.92f + (plant.seed.mod(9)) * 0.02f       // ±8% scale, uuid-stable
            when (plant.archetype) {
                Archetype.PETAL_FLOWER -> petalFlower(anchor, h * jitter)
                Archetype.TULIP -> tulip(anchor, h * jitter)
                Archetype.BELL_FLOWER -> bellFlower(anchor, h * jitter)
                Archetype.HERB_TUFT -> herbTuft(anchor, h * jitter)
                Archetype.BUSH -> bush(anchor, h * jitter)
                Archetype.HEDGE -> hedge(anchor, h * jitter)
                Archetype.PERENNIAL_SHRUB -> shrub(anchor, h * jitter)
                Archetype.TREE -> tree(anchor, h * jitter)
                Archetype.THISTLE_WEED -> thistle(anchor, h * jitter)
                Archetype.ODD_MUSHROOM -> mushroom(anchor, h * jitter)
            }
        }
    }

    private fun DrawScope.stem(a: Offset, h: Float, w: Float = h * .07f) =
        drawLine(GardenPalette.stem, a, Offset(a.x, a.y - h * .55f), strokeWidth = w, cap = StrokeCap.Round)

    private fun DrawScope.leafPair(a: Offset, h: Float) {
        drawOval(GardenPalette.leaf, topLeft = Offset(a.x - h * .28f, a.y - h * .38f), size = Size(h * .24f, h * .12f))
        drawOval(GardenPalette.leaf, topLeft = Offset(a.x + h * .04f, a.y - h * .46f), size = Size(h * .24f, h * .12f))
    }

    private fun DrawScope.petalFlower(a: Offset, h: Float) {
        stem(a, h); leafPair(a, h)
        val c = Offset(a.x, a.y - h * .68f)
        repeat(6) { i ->
            rotate(degrees = i * 60f, pivot = c) {
                drawOval(GardenPalette.petalYellow, topLeft = Offset(c.x - h * .07f, c.y - h * .30f), size = Size(h * .14f, h * .26f))
            }
        }
        drawCircle(Brush.radialGradient(listOf(GardenPalette.petalCenterLight, GardenPalette.petalCenterDark), center = c, radius = h * .12f), radius = h * .12f, center = c)
    }

    private fun DrawScope.tulip(a: Offset, h: Float) {
        stem(a, h); leafPair(a, h)
        val c = Offset(a.x, a.y - h * .66f)
        val cup = Path().apply {
            moveTo(c.x - h * .16f, c.y + h * .10f)
            quadraticBezierTo(c.x - h * .18f, c.y - h * .22f, c.x, c.y - h * .20f)
            quadraticBezierTo(c.x + h * .18f, c.y - h * .22f, c.x + h * .16f, c.y + h * .10f)
            quadraticBezierTo(c.x, c.y + h * .20f, c.x - h * .16f, c.y + h * .10f)
        }
        drawPath(cup, Brush.verticalGradient(listOf(GardenPalette.tulipLight, GardenPalette.tulipDark), startY = c.y - h * .22f, endY = c.y + h * .2f))
    }

    private fun DrawScope.bellFlower(a: Offset, h: Float) {
        stem(a, h); leafPair(a, h)
        listOf(-.10f, .10f).forEachIndexed { i, dx ->
            val c = Offset(a.x + h * dx, a.y - h * (.62f - i * .08f))
            val bell = Path().apply {
                moveTo(c.x - h * .09f, c.y - h * .08f)
                quadraticBezierTo(c.x, c.y - h * .18f, c.x + h * .09f, c.y - h * .08f)
                lineTo(c.x + h * .11f, c.y + h * .08f); lineTo(c.x - h * .11f, c.y + h * .08f); close()
            }
            drawPath(bell, GardenPalette.bellViolet)
        }
    }

    private fun DrawScope.herbTuft(a: Offset, h: Float) {
        listOf(-24f, -10f, 0f, 12f, 26f).forEach { deg ->
            rotate(degrees = deg, pivot = a) {
                drawLine(GardenPalette.leaf, a, Offset(a.x, a.y - h * .6f), strokeWidth = h * .06f, cap = StrokeCap.Round)
            }
        }
    }

    private fun DrawScope.bush(a: Offset, h: Float) {
        val g = Brush.verticalGradient(listOf(GardenPalette.canopyLight, GardenPalette.canopyDark), startY = a.y - h * .7f, endY = a.y)
        drawCircle(g, radius = h * .30f, center = Offset(a.x, a.y - h * .30f))
        drawCircle(g, radius = h * .22f, center = Offset(a.x - h * .24f, a.y - h * .20f))
        drawCircle(g, radius = h * .22f, center = Offset(a.x + h * .24f, a.y - h * .20f))
        drawCircle(Color.White, radius = h * .035f, center = Offset(a.x - h * .1f, a.y - h * .42f))
        drawCircle(Color.White, radius = h * .03f, center = Offset(a.x + h * .14f, a.y - h * .34f))
    }

    private fun DrawScope.hedge(a: Offset, h: Float) {
        val g = Brush.verticalGradient(listOf(GardenPalette.hedgeLight, GardenPalette.hedgeDark), startY = a.y - h * .5f, endY = a.y)
        drawRoundRect(g, topLeft = Offset(a.x - h * .42f, a.y - h * .48f), size = Size(h * .84f, h * .48f), cornerRadius = CornerRadius(h * .16f))
        drawRoundRect(Color(0x22FFFFFF), topLeft = Offset(a.x - h * .36f, a.y - h * .46f), size = Size(h * .72f, h * .10f), cornerRadius = CornerRadius(h * .08f))
    }

    private fun DrawScope.shrub(a: Offset, h: Float) {
        drawLine(GardenPalette.trunk, a, Offset(a.x, a.y - h * .28f), strokeWidth = h * .09f, cap = StrokeCap.Round)
        val g = Brush.verticalGradient(listOf(GardenPalette.canopyLight, GardenPalette.canopyDark), startY = a.y - h * .78f, endY = a.y - h * .2f)
        drawCircle(g, radius = h * .28f, center = Offset(a.x, a.y - h * .48f))
        drawCircle(GardenPalette.tulipLight, radius = h * .04f, center = Offset(a.x - h * .1f, a.y - h * .55f))
        drawCircle(GardenPalette.tulipLight, radius = h * .035f, center = Offset(a.x + h * .12f, a.y - h * .44f))
    }

    private fun DrawScope.tree(a: Offset, h: Float) {
        drawLine(GardenPalette.trunk, a, Offset(a.x, a.y - h * .42f), strokeWidth = h * .11f, cap = StrokeCap.Round)
        val g = Brush.verticalGradient(listOf(GardenPalette.canopyLight, GardenPalette.canopyDark), startY = a.y - h, endY = a.y - h * .3f)
        drawCircle(g, radius = h * .32f, center = Offset(a.x, a.y - h * .68f))
        drawCircle(g, radius = h * .2f, center = Offset(a.x - h * .26f, a.y - h * .56f))
        drawCircle(g, radius = h * .2f, center = Offset(a.x + h * .26f, a.y - h * .56f))
    }

    private fun DrawScope.thistle(a: Offset, h: Float) {
        listOf(-18f, 0f, 16f).forEach { deg ->
            rotate(degrees = deg, pivot = a) {
                drawLine(
                    Brush.verticalGradient(listOf(GardenPalette.weedLight, GardenPalette.weedDark), startY = a.y - h * .6f, endY = a.y),
                    a, Offset(a.x, a.y - h * .58f), strokeWidth = h * .06f, cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(GardenPalette.weedLight, radius = h * .09f, center = Offset(a.x - h * .09f, a.y - h * .58f))
        drawCircle(GardenPalette.weedDark, radius = h * .07f, center = Offset(a.x + h * .10f, a.y - h * .52f))
    }

    private fun DrawScope.mushroom(a: Offset, h: Float) {
        drawRoundRect(GardenPalette.mushroomStem, topLeft = Offset(a.x - h * .07f, a.y - h * .34f), size = Size(h * .14f, h * .34f), cornerRadius = CornerRadius(h * .06f))
        val cap = Path().apply {
            moveTo(a.x - h * .26f, a.y - h * .30f)
            quadraticBezierTo(a.x, a.y - h * .66f, a.x + h * .26f, a.y - h * .30f)
            quadraticBezierTo(a.x, a.y - h * .18f, a.x - h * .26f, a.y - h * .30f)
        }
        drawPath(cap, GardenPalette.mushroomCap)
        drawCircle(Color(0xFFF6DFE7), radius = h * .045f, center = Offset(a.x - h * .09f, a.y - h * .40f))
        drawCircle(Color(0xFFF6DFE7), radius = h * .035f, center = Offset(a.x + h * .08f, a.y - h * .36f))
    }
}

/** Height in px for a tier, relative to tile height. */
fun tierHeight(tileH: Float, tier: SizeTier): Float = when (tier) {
    SizeTier.S -> tileH * 1.4f
    SizeTier.M -> tileH * 2.0f
    SizeTier.L -> tileH * 2.7f
}
