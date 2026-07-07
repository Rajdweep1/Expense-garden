package com.expensegarden.app.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SizeTier
import com.expensegarden.app.game.Tile
import com.expensegarden.app.game.Weather
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun GardenCanvas(
    state: GardenState,
    painter: PlantPainter,
    modifier: Modifier = Modifier,
    onPlantTap: (String) -> Unit = {},
    topReservePx: Float = 300f,
    bottomReservePx: Float = 320f,
    animated: Boolean = true,
) {
    // One clock for everything ambient. phase ∈ [0,1) looping ~8s.
    val transition = rememberInfiniteTransition(label = "garden")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val livePhase = if (animated) phase else 0.25f

    // Pop-in: new uuids since last state spring from 0→1 with overshoot; first composition skips the show.
    val pop = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    LaunchedEffect(state.plants.map { it.txnUuid }) {
        val known = pop.keys.toSet()
        val current = state.plants.map { it.txnUuid }.toSet()
        val firstRun = known.isEmpty() && current.isNotEmpty()
        current.minus(known).forEach { uuid ->
            val anim = Animatable(if (firstRun || !animated) 1f else 0f)
            pop[uuid] = anim
            if (!firstRun && animated) anim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
        pop.keys.retainAll(current)
    }

    val isoState = remember(state.gridRows, state.gridCols) { mutableListOf<IsoMath>() }

    Canvas(
        modifier.pointerInput(state) {
            detectTapGestures { p ->
                val iso = isoState.lastOrNull() ?: return@detectTapGestures
                val tile = iso.tileAt(p.x, p.y)
                state.plants.firstOrNull { it.tile == tile }?.let { onPlantTap(it.txnUuid) }
            }
        },
    ) {
        val iso = IsoMath.fit(state.gridRows, state.gridCols, size.width, size.height, topReservePx, bottomReservePx)
        isoState.clear(); isoState.add(iso)

        // ---- sky ----
        drawRect(Brush.verticalGradient(GardenPalette.sky(state.weather)))
        // sun (dimmer under clouds/dust)
        val sunAlpha = when (state.weather) { Weather.SUNNY -> 1f; else -> .45f }
        val sunC = Offset(size.width * .85f, topReservePx * .38f)
        drawCircle(GardenPalette.sunHalo.copy(alpha = GardenPalette.sunHalo.alpha * sunAlpha * (0.8f + .2f * sin(livePhase * 2f * PI.toFloat()))), radius = 64f, center = sunC)
        drawCircle(GardenPalette.sun.copy(alpha = sunAlpha), radius = 34f, center = sunC)
        // clouds — 2 normally, 3 when overcast; wrap horizontally on the shared clock
        val cloudCount = if (state.weather == Weather.OVERCAST) 3 else 2
        repeat(cloudCount) { i ->
            val speed = .5f + i * .3f
            val cx = ((livePhase * speed + i * .37f) % 1.2f) * size.width * 1.2f - size.width * .1f
            val cy = topReservePx * (.25f + i * .16f)
            cloud(Offset(cx, cy), 1f - i * .18f)
        }
        // sparkles for no-spend days (max 4), twinkling on the clock
        repeat(minOf(state.noSpendDays, 4)) { i ->
            val sx = size.width * (.15f + i * .22f)
            val sy = topReservePx * (.55f + (i % 2) * .2f)
            val a = (sin((livePhase + i * .25f) * 2f * PI.toFloat()) * .5f + .5f)
            sparkle(Offset(sx, sy), 9f, a)
        }

        // ---- back-row trees on the horizon ----
        repeat(state.backRowTreeCount) { i ->
            val tx = size.width * (.30f + i * .20f)
            val base = Offset(tx, iso.tileCenterY(Tile(state.gridRows - 1, 0)) - iso.tileH * 1.2f)
            drawOval(GardenPalette.shadow, topLeft = Offset(base.x - 26f, base.y - 8f), size = Size(52f, 16f))
            val treeH = iso.tileH * (2.6f + minOf(state.trunkTier, 15) * .06f)
            val sway = sin((livePhase * 2f + i * .8f) * PI.toFloat()) * 1.2f
            val plant = Plant("backrow-$i", Archetype.TREE, SizeTier.L, false, Tile(state.gridRows, 0), i * 31)
            with(painter) { drawPlant(plant, base, treeH, sway) }
        }

        // ---- tile field + front walls ----
        for (r in state.gridRows - 1 downTo 0) {
            for (c in 0 until state.gridCols) {
                val cx = iso.tileCenterX(Tile(r, c)); val cy = iso.tileCenterY(Tile(r, c))
                val fill = if ((r + c) % 2 == 0) GardenPalette.grassA(state.weather) else GardenPalette.grassB(state.weather)
                diamond(cx, cy, iso.tileW, iso.tileH, fill)
            }
        }
        val wallH = iso.tileH * .5f
        for (c in 0 until state.gridCols) {                          // front row (row 0) left-facing walls
            val cx = iso.tileCenterX(Tile(0, c)); val cy = iso.tileCenterY(Tile(0, c))
            wall(Offset(cx - iso.tileW / 2, cy), Offset(cx, cy + iso.tileH / 2), wallH, GardenPalette.wallLeft)
        }
        for (r in 0 until state.gridRows) {                          // right column right-facing walls
            val cx = iso.tileCenterX(Tile(r, state.gridCols - 1)); val cy = iso.tileCenterY(Tile(r, state.gridCols - 1))
            wall(Offset(cx, cy + iso.tileH / 2), Offset(cx + iso.tileW / 2, cy), wallH, GardenPalette.wallRight)
        }

        // ---- plants, back to front ----
        state.plants.sortedByDescending { iso.depth(it.tile) }.forEach { plant ->
            val ax = iso.tileCenterX(plant.tile); val ay = iso.tileCenterY(plant.tile) + iso.tileH * .18f
            val anchor = Offset(ax, ay)
            val popScale = pop[plant.txnUuid]?.value ?: 1f
            if (popScale < 1f) {                                     // soil poof while springing in
                drawCircle(GardenPalette.wallLeft.copy(alpha = (1f - popScale) * .5f), radius = iso.tileW * .3f * (0.4f + popScale), center = anchor)
            }
            drawOval(GardenPalette.shadow, topLeft = Offset(ax - iso.tileW * .2f, ay - iso.tileH * .12f), size = Size(iso.tileW * .4f, iso.tileH * .24f))
            val swaySpeed = if (plant.isWeed) 3f else 2f             // weeds fidget
            val sway = sin((livePhase * swaySpeed + (plant.seed.mod(100)) / 100f) * 2f * PI.toFloat()) * 2.4f
            with(painter) { drawPlant(plant, anchor, tierHeight(iso.tileH, plant.sizeTier) * popScale, sway) }
        }

        // ---- butterflies (dodge rewards) on lissajous loops ----
        repeat(state.butterflies) { i ->
            val t = (livePhase + i * .19f) % 1f
            val bx = size.width * .5f + size.width * .32f * sin(2f * PI.toFloat() * t + i)
            val by = topReservePx + (size.height - topReservePx - bottomReservePx) * .3f +
                60f * sin(4f * PI.toFloat() * t + i * 2f)
            butterfly(Offset(bx, by), flap = sin(livePhase * 40f * PI.toFloat()))
        }
    }
}

private fun DrawScope.diamond(cx: Float, cy: Float, w: Float, h: Float, color: Color) {
    val p = Path().apply {
        moveTo(cx, cy - h / 2); lineTo(cx + w / 2, cy); lineTo(cx, cy + h / 2); lineTo(cx - w / 2, cy); close()
    }
    drawPath(p, color)
    drawPath(p, Color(0x14000000), style = Stroke(1f))
}

private fun DrawScope.wall(top1: Offset, top2: Offset, depth: Float, color: Color) {
    val p = Path().apply {
        moveTo(top1.x, top1.y); lineTo(top2.x, top2.y)
        lineTo(top2.x, top2.y + depth); lineTo(top1.x, top1.y + depth); close()
    }
    drawPath(p, color)
}

private fun DrawScope.cloud(c: Offset, scale: Float) {
    drawOval(GardenPalette.cloud, topLeft = Offset(c.x - 44f * scale, c.y - 14f * scale), size = Size(88f * scale, 28f * scale))
    drawOval(GardenPalette.cloud, topLeft = Offset(c.x - 14f * scale, c.y - 24f * scale), size = Size(56f * scale, 30f * scale))
}

private fun DrawScope.sparkle(c: Offset, r: Float, alpha: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y - r); lineTo(c.x + r * .3f, c.y - r * .3f); lineTo(c.x + r, c.y)
        lineTo(c.x + r * .3f, c.y + r * .3f); lineTo(c.x, c.y + r); lineTo(c.x - r * .3f, c.y + r * .3f)
        lineTo(c.x - r, c.y); lineTo(c.x - r * .3f, c.y - r * .3f); close()
    }
    drawPath(p, GardenPalette.sparkle.copy(alpha = GardenPalette.sparkle.alpha * alpha))
}

private fun DrawScope.butterfly(c: Offset, flap: Float) {
    val wing = 7f * (0.4f + 0.6f * kotlin.math.abs(flap))
    drawOval(GardenPalette.butterflyA, topLeft = Offset(c.x - wing - 1.5f, c.y - 4.5f), size = Size(wing, 9f))
    drawOval(GardenPalette.butterflyB, topLeft = Offset(c.x + 1.5f, c.y - 4.5f), size = Size(wing, 9f))
    drawRoundRect(Color(0xFF3F3B52), topLeft = Offset(c.x - 1.4f, c.y - 5f), size = Size(2.8f, 10f), cornerRadius = CornerRadius(1.4f))
}
