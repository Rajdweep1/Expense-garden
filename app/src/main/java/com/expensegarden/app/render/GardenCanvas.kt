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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

    // Model row 0 = front (nearest viewer); IsoMath projects row 0 topmost (farthest).
    // Flip rows at the render boundary so the model's front lands at the bottom of the
    // field. vis is an involution, so it also maps tapped visual tiles back to model tiles.
    fun vis(t: Tile) = Tile(state.gridRows - 1 - t.row, t.col)

    Canvas(
        modifier.pointerInput(state) {
            detectTapGestures { p ->
                val iso = isoState.lastOrNull() ?: return@detectTapGestures
                val tile = vis(iso.tileAt(p.x, p.y))
                state.plants.firstOrNull { it.tile == tile }?.let { onPlantTap(it.txnUuid) }
            }
        },
    ) {
        val iso = IsoMath.fit(state.gridRows, state.gridCols, size.width, size.height, topReservePx, bottomReservePx)
        isoState.clear(); isoState.add(iso)

        // ---- FC-style ocean world: airy horizon fading into calm water ----
        drawRect(Brush.verticalGradient(GardenPalette.sky(state.weather), endY = size.height * .30f))
        drawRect(
            Brush.verticalGradient(GardenPalette.ocean(state.weather), startY = size.height * .22f, endY = size.height),
            topLeft = Offset(0f, size.height * .22f), size = Size(size.width, size.height * .78f),
        )
        // broad light patches on the water (FC stills show these, not just glints)
        listOf(.25f to .38f, .70f to .62f, .45f to .88f).forEach { (fx, fy) ->
            drawOval(Color.White.copy(alpha = .06f), topLeft = Offset(size.width * fx - 180f, size.height * fy - 60f), size = Size(360f, 120f))
        }
        // wave glints — deterministic scatter, twinkling on the clock
        repeat(18) { i ->
            val gx = ((i * 61) % 97) / 97f * size.width
            val gy = size.height * (.26f + ((i * 37) % 89) / 89f * .70f)
            val tw = sin((livePhase * 2f + i * .37f) * 2f * PI.toFloat()) * .5f + .5f
            drawLine(
                GardenPalette.waveGlint.copy(alpha = .10f + .20f * tw),
                Offset(gx, gy), Offset(gx + 14f + 10f * tw, gy), strokeWidth = 3f, cap = StrokeCap.Round,
            )
        }
        // little boats drifting across the water (one behind the island, one in open water)
        repeat(2) { i ->
            val t = (livePhase * (.30f + i * .22f) + i * .53f) % 1.15f
            val bx = t * size.width * 1.15f - size.width * .075f
            val by = size.height * (if (i == 0) .155f else .80f) + sin((livePhase * 4f + i) * PI.toFloat()) * 4f
            boat(Offset(bx, by), scale = if (i == 0) .75f else 1.1f)
        }
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

        // ---- back-row trees on the horizon: stand just behind the field's far edge ----
        repeat(state.backRowTreeCount) { i ->
            val backTile = vis(Tile(state.gridRows - 1, i * 2))     // every other col along the back edge
            val base = Offset(iso.tileCenterX(backTile), iso.tileCenterY(backTile) - iso.tileH * .35f)
            drawOval(GardenPalette.shadow, topLeft = Offset(base.x - 26f, base.y - 8f), size = Size(52f, 16f))
            val treeH = iso.tileH * (2.6f + minOf(state.trunkTier, 15) * .06f)
            val sway = sin((livePhase * 2f + i * .8f) * PI.toFloat()) * 1.2f
            val plant = Plant("backrow-$i", Archetype.TREE, SizeTier.L, false, Tile(state.gridRows, 0), i * 31)
            with(painter) { drawPlant(plant, base, treeH, sway) }
        }

        // ---- tile field + front walls ----
        for (r in state.gridRows - 1 downTo 0) {
            for (c in 0 until state.gridCols) {
                val v = vis(Tile(r, c))
                val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
                val fill = if ((r + c) % 2 == 0) GardenPalette.grassA(state.weather) else GardenPalette.grassB(state.weather)
                diamond(cx, cy, iso.tileW, iso.tileH, fill)
            }
        }
        val wallH = iso.tileH * 1.05f                                // chunky FC-style island slab
        for (c in 0 until state.gridCols) {                          // front row (row 0) left-facing walls
            val v = vis(Tile(0, c))
            val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
            wall(Offset(cx - iso.tileW / 2, cy), Offset(cx, cy + iso.tileH / 2), wallH, GardenPalette.wallLeft, GardenPalette.wallLeftDark)
            drawLine(GardenPalette.soilLip, Offset(cx - iso.tileW / 2, cy), Offset(cx, cy + iso.tileH / 2), strokeWidth = 5f)
            for (t in listOf(.33f, .66f)) {                          // plank seams, like FC's wooden edging
                val px = cx - iso.tileW / 2 + (iso.tileW / 2) * t
                val py = cy + (iso.tileH / 2) * t
                drawLine(Color(0x1F000000), Offset(px, py), Offset(px, py + wallH), strokeWidth = 2.5f)
            }
        }
        for (r in 0 until state.gridRows) {                          // right column right-facing walls
            val v = vis(Tile(r, state.gridCols - 1))
            val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
            wall(Offset(cx, cy + iso.tileH / 2), Offset(cx + iso.tileW / 2, cy), wallH, GardenPalette.wallRight, GardenPalette.wallRightDark)
            drawLine(GardenPalette.soilLip, Offset(cx, cy + iso.tileH / 2), Offset(cx + iso.tileW / 2, cy), strokeWidth = 5f)
            for (t in listOf(.33f, .66f)) {
                val px = cx + (iso.tileW / 2) * t
                val py = cy + iso.tileH / 2 - (iso.tileH / 2) * t
                drawLine(Color(0x1F000000), Offset(px, py), Offset(px, py + wallH), strokeWidth = 2.5f)
            }
        }
        // sea mist hugging the island base, FC-style
        val baseY = iso.tileCenterY(vis(Tile(0, state.gridCols - 1))) + wallH
        listOf(-.18f, .12f, .38f).forEachIndexed { i, dx ->
            val mx = size.width * (.5f + dx) + sin((livePhase + i * .3f) * 2f * PI.toFloat()) * 14f
            drawOval(GardenPalette.mist, topLeft = Offset(mx - 130f, baseY - 34f + i * 10f), size = Size(260f, 68f))
        }

        // ---- quiet props on empty tiles (FC's empty plots are never bare) ----
        val occupied = state.plants.map { it.tile }.toSet()
        for (r in 0 until state.gridRows) for (c in 0 until state.gridCols) {
            val t = Tile(r, c)
            if (t in occupied) continue
            val h = (r * 31 + c * 17 + 7) * 1103515245
            val v = vis(t)
            val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
            when (kotlin.math.abs(h) % 5) {
                0 -> listOf(-14f, 0f, 13f).forEach { deg ->      // small grass tuft
                    rotate(degrees = deg, pivot = Offset(cx, cy)) {
                        drawLine(GardenPalette.leaf.copy(alpha = .55f), Offset(cx, cy), Offset(cx, cy - iso.tileH * .3f), strokeWidth = 3f, cap = StrokeCap.Round)
                    }
                }
                1 -> {                                            // pale pebbles
                    drawCircle(Color(0x66FFFFFF), radius = iso.tileW * .03f, center = Offset(cx - iso.tileW * .08f, cy + iso.tileH * .08f))
                    drawCircle(Color(0x4DFFFFFF), radius = iso.tileW * .022f, center = Offset(cx + iso.tileW * .07f, cy + iso.tileH * .04f))
                }
            }
        }

        // ---- plants, back to front (max model row = farthest visually, drawn first) ----
        state.plants.sortedByDescending { iso.depth(it.tile) }.forEach { plant ->
            val v = vis(plant.tile)
            val ax = iso.tileCenterX(v); val ay = iso.tileCenterY(v) + iso.tileH * .18f
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
            butterfly(Offset(bx, by), flap = sin(livePhase * 40f * PI.toFloat()), sizePx = iso.tileW * .16f)
        }
    }
}

private fun DrawScope.diamond(cx: Float, cy: Float, w: Float, h: Float, color: Color) {
    val p = Path().apply {
        moveTo(cx, cy - h / 2); lineTo(cx + w / 2, cy); lineTo(cx, cy + h / 2); lineTo(cx - w / 2, cy); close()
    }
    drawPath(p, color)
    // soft dashed borders — FC's plot-path lines, garden flavored
    drawPath(p, Color(0x33FFFFFF), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
}

private fun DrawScope.wall(top1: Offset, top2: Offset, depth: Float, light: Color, dark: Color) {
    val p = Path().apply {
        moveTo(top1.x, top1.y); lineTo(top2.x, top2.y)
        lineTo(top2.x, top2.y + depth); lineTo(top1.x, top1.y + depth); close()
    }
    val topY = minOf(top1.y, top2.y)
    drawPath(p, Brush.verticalGradient(listOf(light, dark), startY = topY, endY = maxOf(top1.y, top2.y) + depth))
}

private fun DrawScope.boat(c: Offset, scale: Float) {
    val s = 22f * scale
    val hullPath = Path().apply {
        moveTo(c.x - s, c.y); lineTo(c.x + s, c.y)
        quadraticBezierTo(c.x + s * .7f, c.y + s * .5f, c.x, c.y + s * .5f)
        quadraticBezierTo(c.x - s * .7f, c.y + s * .5f, c.x - s, c.y); close()
    }
    drawPath(hullPath, GardenPalette.hullBrown)
    drawLine(Color(0xFF6B4423), Offset(c.x, c.y - 2f), Offset(c.x, c.y - s * 1.6f), strokeWidth = 3f)
    val sail = Path().apply {
        moveTo(c.x + 3f, c.y - s * 1.55f); lineTo(c.x + 3f + s * .9f, c.y - 4f); lineTo(c.x + 3f, c.y - 4f); close()
    }
    drawPath(sail, GardenPalette.sailCloth)
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

private fun DrawScope.butterfly(c: Offset, flap: Float, sizePx: Float) {
    val wing = sizePx * (0.4f + 0.6f * kotlin.math.abs(flap))
    val h = sizePx * 1.3f
    drawOval(GardenPalette.butterflyA, topLeft = Offset(c.x - wing - sizePx * .1f, c.y - h / 2), size = Size(wing, h))
    drawOval(GardenPalette.butterflyB, topLeft = Offset(c.x + sizePx * .1f, c.y - h / 2), size = Size(wing, h))
    drawRoundRect(Color(0xFF3F3B52), topLeft = Offset(c.x - sizePx * .09f, c.y - h * .55f), size = Size(sizePx * .18f, h * 1.1f), cornerRadius = CornerRadius(sizePx * .09f))
}
