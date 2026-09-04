package com.expensegarden.app.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Plant
import com.expensegarden.app.game.SizeTier
import com.expensegarden.app.game.SpiralTiler
import com.expensegarden.app.game.Tile
import com.expensegarden.app.game.Weather
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.launch

private const val TAU = 2f * PI.toFloat()
private val MONTH_ABBR = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

/** A transient dust/leaf puff where an empty tile was tapped. */
private class TapPuff(val tile: Tile) {
    val anim = Animatable(0f)
}

@Composable
fun GardenCanvas(
    state: GardenState,
    painter: PlantPainter,
    structures: Map<String, ImageBitmap> = emptyMap(),   // 1C.6 house_0..3, keyed by base name
    modifier: Modifier = Modifier,
    onPlantTap: ((String) -> Unit)? = null,
    topReservePx: Float = 300f,
    bottomReservePx: Float = 320f,
    animated: Boolean = true,
    worldMode: Boolean = false,   // 1C.6: square all-time island with a center house, camera roams
    expandFrom: GardenState? = null,          // 1C.7: the SAME txns folded at the previous house level
    onExpansionShown: (() -> Unit)? = null,   // fired once the tween completes, so it never replays
) {
    // One master clock in SECONDS; every motion derives its own period from it, so no
    // two ambient rhythms share a phase and drifting objects never snap on a loop seam.
    // (The old single 8s phase made boats/clouds teleport backward at every wrap and
    // the whole scene breathe in lockstep.) Static callers skip the clock entirely.
    val timeState: State<Float> = if (animated) {
        rememberInfiniteTransition(label = "garden").animateFloat(
            initialValue = 0f, targetValue = 3600f,
            animationSpec = infiniteRepeatable(tween(3_600_000, easing = LinearEasing), RepeatMode.Restart),
            label = "t",
        )
    } else {
        remember { mutableFloatStateOf(47f) }   // an arbitrary frozen moment mid-scene
    }

    // 1C.7: one-shot homestead expansion. 0→1 over 1.5s, then the caller records the level so
    // it never replays. Static callers (greenhouse cards) never pass expandFrom, so ep stays 1.
    val expand = remember(expandFrom) { Animatable(if (expandFrom == null) 1f else 0f) }
    LaunchedEffect(expandFrom) {
        if (expandFrom == null) return@LaunchedEffect
        expand.snapTo(0f)
        expand.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
        onExpansionShown?.invoke()
    }
    val ep = expand.value

    // Pop-in: new uuids since last state spring from 0→1 with overshoot; first composition skips the show.
    val pop = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    // Tap acknowledgment: a quick squash-and-stretch wobble on the touched plant.
    val jiggle = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    val puffs = remember { mutableStateListOf<TapPuff>() }
    val scope = rememberCoroutineScope()
    // 1C.6 revival: when a zombie tile re-folds into a living plant, celebrate loudly.
    val revive = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    val prevArch = remember { mutableStateMapOf<String, Archetype>() }
    LaunchedEffect(state.plants) {
        state.plants.forEach { p ->
            val old = prevArch[p.txnUuid]
            if (animated && old == Archetype.ZOMBIE && p.archetype != Archetype.ZOMBIE) {
                val a = Animatable(0f)
                revive[p.txnUuid] = a
                // 1.4s: long enough to still catch the tail after the txn sheet slides away
                launch { a.animateTo(1f, tween(1400)); revive.remove(p.txnUuid) }
            }
            prevArch[p.txnUuid] = p.archetype
        }
        prevArch.keys.retainAll(state.plants.map { it.txnUuid }.toSet())
    }
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
        jiggle.keys.retainAll(current)
    }

    // ---- camera: pinch-zoom + pan with rubber-band edges; only the interactive home garden gets one ----
    val cameraEnabled = animated && onPlantTap != null
    var zoom by remember { mutableFloatStateOf(if (cameraEnabled) 1.12f else 1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }

    // One iso for gestures, glide, and drawing alike. Null only before the first layout.
    val iso = remember(state.gridRows, state.gridCols, viewport, worldMode) {
        if (viewport == Size.Zero) null
        else if (worldMode) IsoMath.fitHome(state.gridRows, viewport.width, viewport.height, topReservePx, bottomReservePx)
        else IsoMath.fit(state.gridRows, state.gridCols, viewport.width, viewport.height, topReservePx, bottomReservePx)
    }

    // World mode pans over the island's true extent; classic mode keeps the viewport-relative feel.
    fun panRanges(z: Float): Pair<ClosedFloatingPointRange<Float>, ClosedFloatingPointRange<Float>> {
        val rect = iso?.islandRect(state.gridRows, state.gridCols)
        return if (worldMode && rect != null) {
            // Cap the positive-y reach: pulling the island far down only exposes the
            // sky/ocean parallax seam above the frontier — history lives the other way.
            val ry = CameraMath.panRange(rect.top, rect.bottom, viewport.height / 2f, z)
            CameraMath.panRange(rect.left, rect.right, viewport.width / 2f, z) to
                ry.start..minOf(ry.endInclusive, viewport.height * .15f)
        } else {
            val bx = CameraMath.panBoundX(z, viewport.width)
            val by = CameraMath.panBoundY(z, viewport.height)
            (-bx..bx) to (-by..by)
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = CameraMath.clampZoom(zoom * zoomChange, if (worldMode) CameraMath.WORLD_MIN_ZOOM else CameraMath.MIN_ZOOM)
        val (rx, ry) = panRanges(zoom)
        pan = Offset(CameraMath.rubberBand(pan.x + panChange.x, rx), CameraMath.rubberBand(pan.y + panChange.y, ry))
    }
    if (cameraEnabled) {
        LaunchedEffect(Unit) {
            // When the fingers lift, spring any rubber-band overshoot back inside bounds.
            snapshotFlow { transformState.isTransformInProgress }.collect { moving ->
                if (!moving) {
                    val (rx, ry) = panRanges(zoom)
                    val target = Offset(CameraMath.clampPan(pan.x, rx), CameraMath.clampPan(pan.y, ry))
                    if (target != pan) {
                        val start = pan
                        Animatable(0f).animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f)) {
                            pan = lerp(start, target, value)
                        }
                    }
                }
            }
        }
    }

    // 1C.6: the house-anchored fitHome() keeps every planted tile fixed on screen as a
    // ring is added, so the old serpentine growth-glide compensation is gone entirely.

    // Model row 0 = front (nearest viewer); IsoMath projects row 0 topmost (farthest).
    // Flip rows at the render boundary so the model's front lands at the bottom of the
    // field. vis is an involution, so it also maps tapped visual tiles back to model tiles.
    fun vis(t: Tile) = Tile(state.gridRows - 1 - t.row, t.col)

    // 1C.6 homestead geometry: the house block + the 4 backyard (grove) tiles exist only in
    // world mode on the square island; both are reserved (never planted or propped).
    // 1C.7: the block is footprint×footprint, growing 2→3→4 with the house level.
    val side = state.gridRows
    val foot = SpiralTiler.footprint(state.houseLevel)
    val houseLo = (side - foot) / 2
    val houseTiles = if (worldMode) SpiralTiler.houseTiles(side, foot) else emptySet()
    val backyardTiles = if (worldMode) SpiralTiler.backyardTiles(side, foot) else emptySet()

    var canvasModifier = modifier.onSizeChanged { viewport = it.toSize() }
    if (cameraEnabled) canvasModifier = canvasModifier.transformable(transformState)
    if (onPlantTap != null) {
        canvasModifier = canvasModifier.pointerInput(state) {
            detectTapGestures { p ->
                val isoNow = iso ?: return@detectTapGestures
                // Undo the camera before hit-testing: island layer = translate(cam) → scale(zoom @ center).
                val cx = viewport.width / 2f
                val cy = viewport.height / 2f
                val drift = idleDrift(timeState.value, cameraEnabled)
                val wx = CameraMath.screenToWorldX(p.x, pan.x + drift.x, zoom, cx)
                val wy = CameraMath.screenToWorldY(p.y, pan.y + drift.y, zoom, cy)
                val tile = vis(isoNow.tileAt(wx, wy))
                val plant = state.plants.firstOrNull { it.tile == tile }
                if (plant != null) {
                    scope.launch {
                        jiggle.getOrPut(plant.txnUuid) { Animatable(1f) }.run {
                            snapTo(0f)
                            animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                    onPlantTap(plant.txnUuid)
                } else if (tile.row in 0 until state.gridRows && tile.col in 0 until state.gridCols && tile !in houseTiles) {
                    val puff = TapPuff(tile)
                    puffs += puff
                    scope.launch {
                        puff.anim.animateTo(1f, tween(480))
                        puffs -= puff
                    }
                }
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()

    Canvas(canvasModifier) {
        val t = timeState.value
        val iso = iso ?: if (worldMode)
            IsoMath.fitHome(state.gridRows, size.width, size.height, topReservePx, bottomReservePx)
        else
            IsoMath.fit(state.gridRows, state.gridCols, size.width, size.height, topReservePx, bottomReservePx)

        val center = Offset(size.width / 2f, size.height / 2f)
        val cam = if (cameraEnabled) pan + idleDrift(t, true) else Offset.Zero
        val zoomNow = if (cameraEnabled) zoom else 1f

        // ---- viewport culling: on a big island only rows that can touch the screen draw.
        // Margins cover tall sprites poking in from above and the slab wall below.
        val worldTopY = CameraMath.screenToWorldY(-iso.tileH * 4f, cam.y, zoomNow, center.y)
        val worldBotY = CameraMath.screenToWorldY(size.height + iso.tileH * 2.5f, cam.y, zoomNow, center.y)
        val vLo = floor((worldTopY - iso.originY) / (iso.tileH / 2f)).toInt() - (state.gridCols - 1) - 1
        val vHi = floor((worldBotY - iso.originY) / (iso.tileH / 2f)).toInt() + 2
        val visRows = maxOf(0, vLo)..minOf(state.gridRows - 1, vHi)
        fun rowVisible(modelRow: Int) = (state.gridRows - 1 - modelRow) in visRows
        val visPlants = state.plants.filter { rowVisible(it.tile.row) }

        // ================= LAYER: sky (slowest parallax, never zooms — it's atmosphere) =================
        withTransform({ translate(cam.x * .12f, cam.y * .12f) }) {
            drawRect(
                Brush.verticalGradient(GardenPalette.sky(state.weather), endY = size.height * .30f),
                topLeft = Offset(-size.width * .3f, -size.height * .3f),
                size = Size(size.width * 1.6f, size.height * .60f + size.height * .3f),
            )
        }

        // ================= LAYER: sun, clouds, birds (distant — pan a little, no zoom) =================
        withTransform({ translate(cam.x * .22f, cam.y * .22f) }) {
            val sunAlpha = when (state.weather) { Weather.SUNNY -> 1f; else -> .45f }
            val sunC = Offset(size.width * .85f, topReservePx * .38f)
            drawCircle(GardenPalette.sunHalo.copy(alpha = GardenPalette.sunHalo.alpha * sunAlpha * (0.8f + .2f * sin(t / 4.9f * TAU))), radius = 64f, center = sunC)
            drawCircle(GardenPalette.sun.copy(alpha = sunAlpha), radius = 34f, center = sunC)
            val cloudCount = if (state.weather == Weather.OVERCAST) 3 else 2
            repeat(cloudCount) { i ->
                val cxp = ((t / (51f + i * 17f) + i * .37f) % 1.2f) * size.width * 1.2f - size.width * .1f
                val cyp = topReservePx * (.25f + i * .16f)
                cloud(Offset(cxp, cyp), 1f - i * .18f)
            }
            // a small flock crosses the sky every ~13s — seeded per crossing, alternating
            // direction; every third cycle the sky stays quiet while one bird visits the grove
            if (animated) {
                val cycle = floor(t / 13f).toInt()
                val progress = (t % 13f) / 6.5f
                if (cycle % 3 != 2 && progress < 1f) {
                    val h = abs(cycle * 1103515245 + 12345)
                    val dir = if (h % 2 == 0) 1f else -1f
                    val flightY = size.height * (.06f + (h % 13) / 13f * .12f)
                    val flightX =
                        if (dir > 0) -70f + progress * (size.width + 140f)
                        else size.width + 70f - progress * (size.width + 140f)
                    repeat(3) { k ->
                        val trail = Offset(-dir * k * 36f, (k % 2) * 13f + k * 4f)
                        bird(Offset(flightX, flightY) + trail, flap = sin((t * 4.6f + k * .8f) * TAU))
                    }
                }
            }
            // sparkles for no-spend days (max 4), twinkling on their own rhythm
            repeat(minOf(state.noSpendDays, 4)) { i ->
                val sx = size.width * (.15f + i * .22f)
                val sy = topReservePx * (.55f + (i % 2) * .2f)
                val a = sin((t / 3.9f + i * .25f) * TAU) * .5f + .5f
                sparkle(Offset(sx, sy), 9f, a)
            }
        }

        // ================= LAYER: ocean (mid parallax, zooms with the world) =================
        withTransform({
            translate(cam.x * .45f, cam.y * .45f)
            scale(zoomNow, zoomNow, pivot = center)
        }) {
            drawRect(
                Brush.verticalGradient(GardenPalette.ocean(state.weather), startY = size.height * .22f, endY = size.height),
                topLeft = Offset(-size.width * .5f, size.height * .22f),
                size = Size(size.width * 2f, size.height * 1.3f),
            )
            // depth: the water darkens away from the island
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x3D1F5E8F)), startY = size.height * .55f, endY = size.height * 1.4f),
                topLeft = Offset(-size.width * .5f, size.height * .55f),
                size = Size(size.width * 2f, size.height * .95f),
            )
            // broad light patches on the water (FC stills show these, not just glints)
            listOf(.25f to .38f, .70f to .62f, .45f to .88f).forEach { (fx, fy) ->
                drawOval(Color.White.copy(alpha = .06f), topLeft = Offset(size.width * fx - 180f, size.height * fy - 60f), size = Size(360f, 120f))
            }
            // wave glints — deterministic scatter, each twinkling on its own offset
            repeat(18) { i ->
                val gx = ((i * 61) % 97) / 97f * size.width
                val gy = size.height * (.26f + ((i * 37) % 89) / 89f * .70f)
                val tw = sin(t / 5.3f * TAU + i * 2.4f) * .5f + .5f
                drawLine(
                    GardenPalette.waveGlint.copy(alpha = .10f + .20f * tw),
                    Offset(gx, gy), Offset(gx + 14f + 10f * tw, gy), strokeWidth = 3f, cap = StrokeCap.Round,
                )
            }
            // little boats drifting across the water — periods 34s/47s, continuous in t (no wrap-snap)
            repeat(2) { i ->
                val prog = ((t + i * 18f) / (34f + i * 13f)) % 1.15f
                val bx = prog * size.width * 1.15f - size.width * .075f
                val by = size.height * (if (i == 0) .155f else .80f) + sin((t / 2.7f + i) * TAU) * 4f
                boat(Offset(bx, by), scale = if (i == 0) .75f else 1.1f)
            }
        }

        // ================= LAYER: the island — full camera (pan 1x, zoom @ center) =================
        withTransform({
            translate(cam.x, cam.y)
            scale(zoomNow, zoomNow, pivot = center)
        }) {

            // ---- back-row trees on the horizon (greenhouse/monthly only; world mode shelters
            // ---- the grove in the backyard behind the house — drawn at house depth below) ----
            if (!worldMode && rowVisible(state.gridRows - 1)) repeat(state.backRowTreeCount) { i ->
                val backTile = vis(Tile(state.gridRows - 1, i * 2))     // every other col along the back edge
                val base = Offset(iso.tileCenterX(backTile), iso.tileCenterY(backTile) - iso.tileH * .35f)
                drawOval(GardenPalette.shadow, topLeft = Offset(base.x - 26f, base.y - 8f), size = Size(52f, 16f))
                val treeH = iso.tileH * (2.6f + minOf(state.trunkTier, 15) * .06f)
                val sway = sin((t / 4.3f + i * .8f) * TAU) * 1.2f
                val plant = Plant("backrow-$i", Archetype.TREE, SizeTier.L, false, Tile(state.gridRows, 0), i * 31)
                with(painter) { drawPlant(plant, base, treeH, sway) }
            }

            // ---- tile field: soft pillow relief instead of flat fills ----
            for (r in state.gridRows - 1 downTo 0) {
                if (!rowVisible(r)) continue
                for (c in 0 until state.gridCols) {
                    val v = vis(Tile(r, c))
                    val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
                    val fill = if ((r + c) % 2 == 0) GardenPalette.grassA(state.weather) else GardenPalette.grassB(state.weather)
                    diamond(cx, cy, iso.tileW, iso.tileH, fill)
                    // seeded speckle texture — FC tiles are never flat color
                    val th = abs((r * 53 + c * 29 + 11) * 1103515245)
                    repeat(3) { k ->
                        val hx = abs(th / (k + 1) + k * 7919)
                        val sx = cx + ((hx % 97) / 97f - .5f) * iso.tileW * .52f
                        val sy = cy + (((hx / 97) % 89) / 89f - .5f) * iso.tileH * .52f
                        drawCircle(Color(0x142E5B25), radius = 2.2f + (hx % 3), center = Offset(sx, sy))
                    }
                    if (th % 3 == 0) {
                        val gx = cx + ((th % 61) / 61f - .5f) * iso.tileW * .4f
                        val gy = cy + (((th / 61) % 53) / 53f - .5f) * iso.tileH * .4f
                        drawLine(Color(0x2ECDEBA4), Offset(gx - 4f, gy), Offset(gx + 4f, gy - 2f), strokeWidth = 2f, cap = StrokeCap.Round)
                    }
                }
            }
            val wallH = iso.tileH * IsoMath.WALL_UNITS                   // chunky FC-style island slab
            if (rowVisible(0)) for (c in 0 until state.gridCols) {       // front row (row 0) left-facing walls
                val v = vis(Tile(0, c))
                val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
                wall(Offset(cx - iso.tileW / 2, cy), Offset(cx, cy + iso.tileH / 2), wallH, GardenPalette.wallLeft, GardenPalette.wallLeftDark)
                drawLine(GardenPalette.soilLip, Offset(cx - iso.tileW / 2, cy), Offset(cx, cy + iso.tileH / 2), strokeWidth = 5f)
                for (s in listOf(.33f, .66f)) {                          // plank seams, like FC's wooden edging
                    val px = cx - iso.tileW / 2 + (iso.tileW / 2) * s
                    val py = cy + (iso.tileH / 2) * s
                    drawLine(Color(0x1F000000), Offset(px, py), Offset(px, py + wallH), strokeWidth = 2.5f)
                }
            }
            for (r in 0 until state.gridRows) {                          // right column right-facing walls
                if (!rowVisible(r)) continue
                val v = vis(Tile(r, state.gridCols - 1))
                val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
                wall(Offset(cx, cy + iso.tileH / 2), Offset(cx + iso.tileW / 2, cy), wallH, GardenPalette.wallRight, GardenPalette.wallRightDark)
                drawLine(GardenPalette.soilLip, Offset(cx, cy + iso.tileH / 2), Offset(cx + iso.tileW / 2, cy), strokeWidth = 5f)
                for (s in listOf(.33f, .66f)) {
                    val px = cx + (iso.tileW / 2) * s
                    val py = cy + iso.tileH / 2 - (iso.tileH / 2) * s
                    drawLine(Color(0x1F000000), Offset(px, py), Offset(px, py + wallH), strokeWidth = 2.5f)
                }
            }

            // ---- shore foam: soft contours breathing along the waterline ----
            if (rowVisible(0)) {
                val lc = vis(Tile(0, 0)); val bcT = vis(Tile(0, state.gridCols - 1))
                val rcT = vis(Tile(2.coerceAtMost(state.gridRows - 1), state.gridCols - 1))
                repeat(2) { i ->
                    val off = wallH + 8f + i * 10f + sin((t / (3.1f + i * 1.3f)) * TAU + i) * 3.5f
                    val foam = Path().apply {
                        moveTo(iso.tileCenterX(lc) - iso.tileW / 2f, iso.tileCenterY(lc) + off)
                        lineTo(iso.tileCenterX(bcT), iso.tileCenterY(bcT) + iso.tileH / 2f + off)
                        lineTo(iso.tileCenterX(rcT) + iso.tileW / 2f, iso.tileCenterY(rcT) + off)
                    }
                    drawPath(foam, Color.White.copy(alpha = .15f - i * .05f), style = Stroke(4.5f - i * 1.5f, cap = StrokeCap.Round))
                }
            }

            // ---- quiet props on empty tiles (FC's empty plots are never bare) ----
            val occupied = state.plants.map { it.tile }.toSet()
            for (r in 0 until state.gridRows) for (c in 0 until state.gridCols) {
                if (!rowVisible(r)) continue
                val tile = Tile(r, c)
                if (tile in occupied || tile in houseTiles || tile in backyardTiles) continue
                val h = (r * 31 + c * 17 + 7) * 1103515245
                val v = vis(tile)
                val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
                when (abs(h) % 5) {
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

            // ---- dust puffs where an empty tile was tapped ----
            puffs.forEach { puff ->
                val v = vis(puff.tile)
                val cx = iso.tileCenterX(v); val cy = iso.tileCenterY(v)
                val p = puff.anim.value
                drawCircle(Color.White.copy(alpha = (1f - p) * .30f), radius = iso.tileW * (.08f + .22f * p), center = Offset(cx, cy))
                repeat(3) { k ->
                    val ang = (-.5f + (k - 1) * .45f) * PI.toFloat()
                    val d = iso.tileW * .18f * p
                    drawCircle(
                        GardenPalette.leaf.copy(alpha = (1f - p) * .8f), radius = 3.5f,
                        center = Offset(cx + kotlin.math.cos(ang) * d, cy + sin(ang) * d * 1.2f),
                    )
                }
            }

            // ---- homestead: house sprite + backyard grove, drawn at the block's depth so
            // ---- plants behind it occlude correctly and plants in front sit over it ----
            // 1C.7 expansion: plants glide from their old tiles to their new ones. Positions
            // are lerped in SCREEN space between each state's OWN iso, so a changed island
            // side needs no special-casing. The ground/slab renders at the new size for the
            // whole tween — the land is already there; the house pushes the garden onto it.
            val isoFrom = expandFrom?.let {
                IsoMath.fitHome(it.gridRows, size.width, size.height, topReservePx, bottomReservePx)
            }
            val fromTiles = expandFrom?.plants?.associate { it.txnUuid to it.tile } ?: emptyMap()
            val fromRows = expandFrom?.gridRows ?: 0
            fun visFrom(t: Tile) = Tile(fromRows - 1 - t.row, t.col)
            val expanding = expandFrom != null && isoFrom != null && ep < 1f

            /** Bottom-center of a plant, lerped from its previous tile while expanding. */
            fun anchorOf(plant: Plant): Offset {
                val v = vis(plant.tile)
                val nx = iso.tileCenterX(v)
                val ny = iso.tileCenterY(v) + iso.tileH * .18f
                val ft = fromTiles[plant.txnUuid]
                if (!expanding || ft == null) return Offset(nx, ny)
                val fv = visFrom(ft)
                val im = isoFrom!!                          // local capture: no smart-cast reliance
                val ox = im.tileCenterX(fv)
                val oy = im.tileCenterY(fv) + im.tileH * .18f
                return Offset(ox + (nx - ox) * ep, oy + (ny - oy) * ep)
            }

            /** House anchor for a given state under its own projection. */
            fun houseAnchor(s: GardenState, im: IsoMath, flip: (Tile) -> Tile): Offset {
                val f = SpiralTiler.footprint(s.houseLevel)
                val lo = (s.gridRows - f) / 2
                val corners = listOf(
                    Tile(lo, lo), Tile(lo, lo + f - 1),
                    Tile(lo + f - 1, lo), Tile(lo + f - 1, lo + f - 1),
                )
                return Offset(
                    corners.map { im.tileCenterX(flip(it)) }.average().toFloat(),
                    corners.maxOf { im.tileCenterY(flip(it)) } + im.tileH * .5f,
                )
            }

            val houseBmp = if (worldMode) (structures["house_${(state.houseLevel - 1).coerceIn(0, 3)}"] ?: structures["house_0"]) else null
            val houseRowsVisible = (houseLo until houseLo + foot).any { rowVisible(it) }
            val hNew = houseAnchor(state, iso, ::vis)
            val hAnchor = if (expanding) {
                val hOld = houseAnchor(expandFrom!!, isoFrom!!, ::visFrom)
                Offset(hOld.x + (hNew.x - hOld.x) * ep, hOld.y + (hNew.y - hOld.y) * ep)
            } else hNew
            val ds = this
            val drawHomestead = {
                with(ds) {
                    val yard = backyardTiles.sortedBy { it.col }
                    repeat(minOf(state.backRowTreeCount, yard.size)) { i ->
                        val bt = yard[i]
                        // Pushed back (-.30 tileH) and shortened as the house grows so the
                        // taller upper levels — villa cupola especially — clear the canopy.
                        val base = Offset(iso.tileCenterX(vis(bt)), iso.tileCenterY(vis(bt)) - iso.tileH * .12f)
                        drawOval(GardenPalette.shadow, topLeft = Offset(base.x - 26f, base.y - 8f), size = Size(52f, 16f))
                        val groveScale = 1f - .07f * (state.houseLevel - 1).coerceIn(0, 3)
                        val treeH = iso.tileH * (2.6f + minOf(state.trunkTier, 15) * .06f) * groveScale
                        val sway = sin((t / 4.3f + i * .8f) * TAU) * 1.2f
                        with(painter) { drawPlant(Plant("grove-$i", Archetype.TREE, SizeTier.L, false, Tile(0, 0), i * 31), base, treeH, sway) }
                    }
                    // Draw-size follows the footprint ladder (1C.7 §4). Level 2 slightly
                    // overhangs its 2×2 plot, as in 1C.6.
                    // The villa's 5.2 is deliberately more than the ladder would suggest: the
                    // island's BACK CORNER projects to the topmost point of the diamond, dead
                    // centre — directly above the house. Plants standing there are correctly
                    // drawn behind the house but were tall enough to clear its roofline, so
                    // their faces appeared to sit on the roof. Raising the silhouette is the
                    // only fix that preserves growth-invariance: reserving those tiles cannot
                    // work, because they are island-EDGE relative and therefore move outward
                    // every time a ring is added, which would shift already-planted tiles.
                    fun spanOf(level: Int) = iso.tileW * when (level.coerceIn(1, 4)) {
                        1 -> 2.0f
                        2 -> 2.4f
                        3 -> 3.2f
                        else -> 5.2f
                    }
                    val newSpan = spanOf(state.houseLevel)
                    val houseSpan = if (expanding) {
                        val old = spanOf(expandFrom!!.houseLevel)
                        old + (newSpan - old) * ep
                    } else newSpan
                    // EXACTLY ONE house sprite is visible at any instant. The levels have
                    // different silhouettes — roof shape, height, width — so ANY alpha blend of
                    // the pair shows the union of both outlines: a house wearing two roofs,
                    // which reads as a broken model rather than a transition. (Cross-fading at
                    // (1−ep)/ep also dipped coverage to 0.75 and showed ground through the walls;
                    // both attempts failed for the same underlying reason.) So: hard-swap at the
                    // midpoint and hide the cut behind a construction puff — the same idiom the
                    // tap dust uses, and how the genre has always concealed a building swap.
                    val oldBmp = if (expanding) structures["house_${(expandFrom!!.houseLevel - 1).coerceIn(0, 3)}"] else null
                    val shownBmp = if (expanding && ep < .5f) (oldBmp ?: houseBmp) else houseBmp
                    shownBmp?.let { house(it, hAnchor.x, hAnchor.y, houseSpan, GardenPalette.shadow.copy(alpha = .22f)) }
                    if (expanding) {
                        // Dust swells across ep .30–.70 and peaks exactly on the cut at .50.
                        val s = ((ep - .30f) / .40f).coerceIn(0f, 1f)
                        val a = sin(s * PI.toFloat())
                        val puffY = hAnchor.y - houseSpan * .14f
                        repeat(5) { k ->
                            val r = houseSpan * (.15f + .20f * s) * (1f - .12f * abs(k - 2))
                            drawCircle(
                                Color.White.copy(alpha = a * .60f), radius = r,
                                center = Offset(hAnchor.x + (k - 2) * houseSpan * .21f, puffY - s * houseSpan * .05f),
                            )
                        }
                        repeat(6) { k ->
                            val ang = (k / 6f) * TAU
                            val d = houseSpan * (.20f + .30f * s)
                            drawCircle(
                                GardenPalette.leaf.copy(alpha = a * .70f), radius = houseSpan * .022f,
                                center = Offset(hAnchor.x + cos(ang) * d, puffY + sin(ang) * d * .45f),
                            )
                        }
                    }
                }
            }
            var houseDrawn = false

            // 1C.6: cap animated zombies (nearest first) so a rough month isn't a whole horde in motion.
            val animatedZombies = visPlants.filter { it.archetype == Archetype.ZOMBIE }
                .sortedByDescending { it.tile.row }.take(4).map { it.txnUuid }.toSet()

            // ---- plants, back to front ----
            // Draw order AND the house's insertion point both key off the anchor's SCREEN Y,
            // never the tile row. Isometric depth runs along row+col diagonals, so a row-only
            // test misfiles any plant whose column pulls it behind the house. Short plants hid
            // that for two phases; the 1C.7 villa stands ~8 tile-heights tall, so a misfiled
            // plant no longer lands beside the house — it lands on its wall or roof.
            // Ground-position Y is the correct depth for a billboard, and it also keeps the
            // ordering honest mid-expansion while anchors are still lerping.
            // The house is deliberately NOT interleaved into this loop. Per-plant depth is
            // correct for plants, but the house is a single tall billboard: any plant sorted
            // in front of it paints across the sprite, and because plants sit at all heights
            // the result was horizontal seams — foliage and faces banding the house under the
            // eave and under the balcony, so it read as three stacked slabs with garden
            // showing through the joins. A landmark building must render whole, so it is drawn
            // after every plant (see the drawHomestead() call below the loop). The cost is
            // that plants standing directly in front of it lose their tops behind the wall —
            // Rajdweep's explicit call: an unbroken house beats a few occluded plants.
            visPlants.map { it to anchorOf(it) }.sortedBy { it.second.y }.forEach { (plant, anchor) ->
                val ax = anchor.x; val ay = anchor.y
                val popScale = pop[plant.txnUuid]?.value ?: 1f
                if (popScale < 1f) {                                     // soil poof while springing in
                    drawCircle(GardenPalette.wallLeft.copy(alpha = (1f - popScale) * .5f), radius = iso.tileW * .3f * (0.4f + popScale), center = anchor)
                }
                // grounded contact shadow: a broad soft pool + a tight dark core
                drawOval(GardenPalette.shadow.copy(alpha = .12f), topLeft = Offset(ax - iso.tileW * .22f, ay - iso.tileH * .13f), size = Size(iso.tileW * .44f, iso.tileH * .26f))
                drawOval(GardenPalette.shadow.copy(alpha = .20f), topLeft = Offset(ax - iso.tileW * .13f, ay - iso.tileH * .08f), size = Size(iso.tileW * .26f, iso.tileH * .16f))
                revive[plant.txnUuid]?.value?.let { r ->      // expanding ring + sparkles as color returns
                    drawCircle(Color(0xFFFFF3C0).copy(alpha = (1f - r) * .55f), radius = iso.tileW * (.15f + .55f * r), center = anchor, style = Stroke(5f * (1f - r) + 1f))
                    repeat(5) { k ->
                        val ang = k / 5f * TAU + r * 2f
                        sparkle(Offset(anchor.x + cos(ang) * iso.tileW * .34f * r, anchor.y - iso.tileH * .3f - sin(ang) * iso.tileH * .5f * r), 7f, 1f - r)
                    }
                }

                // each plant breathes and leans on its own seeded rhythm; weeds fidget faster
                val swayPeriod = 3.1f + plant.seed.mod(7) * .3f
                val speedMul = if (plant.isWeed) 1.6f else 1f
                val ph = plant.seed.mod(628) / 100f
                val isZombie = plant.archetype == Archetype.ZOMBIE
                val zAnim = isZombie && plant.txnUuid in animatedZombies
                // zombies shamble: a slow heavy lean with an occasional lurch-dip, never a happy
                // breath. Beyond the animated cap a zombie just holds a frozen lean.
                val lean = when {
                    zAnim -> sin(t / 1.9f * TAU + ph) * 1.1f + sin(t / 7.3f * TAU + ph * 2f) * 2.4f
                    isZombie -> 1.2f
                    else -> sin(t / swayPeriod * speedMul * TAU + ph) * (2.2f + plant.seed.mod(5) * .3f) * (if (plant.isWeed) 1.25f else 1f)
                }
                val breathAmp = when (plant.sizeTier) { SizeTier.S -> .026f; SizeTier.M -> .020f; SizeTier.L -> .013f }
                val breath = when {
                    zAnim -> .035f * maxOf(0f, sin(t / 3.7f * TAU + ph)) - .01f
                    isZombie -> 0f
                    else -> sin(t / (swayPeriod * .618f) * TAU + ph * 1.7f) * breathAmp
                }
                val j = jiggle[plant.txnUuid]?.value ?: 1f               // tap wobble: squash then springy stretch
                val squashY = 1f + breath - (1f - j) * .16f
                val squashX = 1f - breath * .6f + (1f - j) * .11f
                withTransform({ scale(squashX, squashY, pivot = anchor) }) {
                    val revivePop = revive[plant.txnUuid]?.value?.let { .22f * sin(it * PI.toFloat()) } ?: 0f
                    with(painter) { drawPlant(plant, anchor, tierHeight(iso.tileH, plant.sizeTier) * popScale * (1f + revivePop), lean) }
                }
                // a few grass blades overlap the base so the sprite sits IN the ground, not on it
                repeat(3) { b ->
                    val off = ((plant.seed + b * 37).mod(21) - 10) / 10f
                    val bx = ax + off * iso.tileW * .13f
                    val bh = iso.tileH * (.14f + (plant.seed + b).mod(4) * .03f)
                    rotate(degrees = lean * .5f + off * 8f, pivot = Offset(bx, ay)) {
                        drawLine(GardenPalette.leaf.copy(alpha = .85f), Offset(bx, ay + 2f), Offset(bx, ay - bh), strokeWidth = 3.5f, cap = StrokeCap.Round)
                    }
                }
            }
            // ---- month signposts: little wooden signs where each month's growth began ----
            // Drawn in TWO passes, split around the house by the same ground-depth rule the
            // plants use. A signpost standing behind the homestead has to be occluded by it,
            // or its label reads as a sticker on the wall — very visible now the houses are
            // solid. Ones nearer the viewer than the house's base still draw over it, so a
            // marker on the front tiles is not swallowed by the building's plinth.
            fun drawMarkers(behind: Boolean) {
            state.monthMarkers.forEach { m ->
                if (!rowVisible(m.tile.row)) return@forEach
                val v = vis(m.tile)
                val px = iso.tileCenterX(v) - iso.tileW * .40f
                val py = iso.tileCenterY(v) + iso.tileH * .10f
                if ((py <= hAnchor.y) != behind) return@forEach
                val postH = iso.tileH * .62f
                drawLine(GardenPalette.hullBrown, Offset(px, py), Offset(px, py - postH), strokeWidth = 4.5f, cap = StrokeCap.Round)
                val plateW = iso.tileW * .34f; val plateH = iso.tileH * .42f
                val plateTL = Offset(px - plateW / 2f, py - postH - plateH * .55f)
                drawRoundRect(GardenPalette.hullBrown, topLeft = plateTL, size = Size(plateW, plateH), cornerRadius = CornerRadius(5f))
                drawRoundRect(Color(0x33000000), topLeft = plateTL, size = Size(plateW, plateH), cornerRadius = CornerRadius(5f), style = Stroke(2f))
                val label = MONTH_ABBR[m.monthKey.substringAfter("-").toInt() - 1]
                val layout = textMeasurer.measure(label, TextStyle(fontSize = (plateH * .5f).toSp(), fontWeight = FontWeight.Bold, color = Color(0xFFFFF3DC)))
                drawText(layout, topLeft = Offset(plateTL.x + (plateW - layout.size.width) / 2f, plateTL.y + (plateH - layout.size.height) / 2f))
            }
            }

            drawMarkers(behind = true)
            // house had no plants in front of it (or all front rows culled) — draw it now.
            // Sole draw path for the homestead: after every plant, so the house is never sliced.
            if (houseBmp != null && houseRowsVisible && !houseDrawn) drawHomestead()
            drawMarkers(behind = false)

            // ---- bees: orbit a flower head, then hop to the next (stable plant list, so
            // ---- panning never teleports a bee; it just goes off-screen with its flower) ----
            if (animated) {
                val flowers = state.plants.filter {
                    it.archetype == Archetype.PETAL_FLOWER || it.archetype == Archetype.TULIP || it.archetype == Archetype.BELL_FLOWER
                }
                if (flowers.isNotEmpty()) {
                    val beeCount = minOf(3, 1 + flowers.size / 8)
                    repeat(beeCount) { i ->
                        val cycleLen = 8.5f + i * 2.3f
                        val cycleIdx = floor((t + i * 13f) / cycleLen).toInt()
                        val u = ((t + i * 13f) % cycleLen) / cycleLen
                        fun flowerAt(k: Int) = flowers[abs(k * 31 + i * 17) % flowers.size]
                        val cur = flowerAt(cycleIdx); val next = flowerAt(cycleIdx + 1)
                        fun head(pl: Plant): Offset {
                            val v = vis(pl.tile)
                            return Offset(
                                iso.tileCenterX(v),
                                iso.tileCenterY(v) + iso.tileH * .18f - tierHeight(iso.tileH, pl.sizeTier) * .62f,
                            )
                        }
                        val travel = .86f
                        val orbit = Offset(
                            cos((t / 1.05f + i * .7f) * TAU) * iso.tileW * .17f,
                            sin((t / 1.05f + i * .7f) * TAU) * iso.tileH * .13f,
                        )
                        val pos = if (u < travel) head(cur) + orbit
                        else {
                            val f = (u - travel) / (1f - travel)
                            lerp(head(cur) + orbit, head(next), f * f * (3f - 2f * f))
                        }
                        val onScreenFlower = if (u < travel) cur else next
                        if (rowVisible(onScreenFlower.tile.row)) bee(pos, iso.tileW * .055f, flap = sin(t * 11f * TAU))
                    }
                }

                // ---- one dragonfly darting between hover points off the frontier's shore ----
                if (rowVisible(state.gridRows - 1)) {
                    val fr = vis(Tile(state.gridRows - 1, state.gridCols - 1))
                    val base = Offset(iso.tileCenterX(fr) + iso.tileW * 1.1f, iso.tileCenterY(fr) + iso.tileH * .6f)
                    fun hover(k: Int) = base + Offset(sin(k * 3.7f) * iso.tileW * .9f, cos(k * 2.3f) * iso.tileH * .8f)
                    val seg = floor(t / 2.4f).toInt()
                    val du = (t % 2.4f) / 2.4f
                    val dPos = if (du < .78f)
                        hover(seg) + Offset(sin(t * 9f * TAU) * 2.2f, cos(t * 7.3f * TAU) * 2.2f)
                    else {
                        val f = (du - .78f) / .22f
                        lerp(hover(seg), hover(seg + 1), f * f * (3f - 2f * f))
                    }
                    dragonfly(dPos, iso.tileW * .075f, wingPhase = sin(t * 23f * TAU))
                }

                // ---- every third bird cycle, one bird visits the grove tree instead ----
                val groveTile = if (worldMode) backyardTiles.minByOrNull { it.col } else Tile(state.gridRows - 1, 0)
                if (state.backRowTreeCount > 0 && groveTile != null && rowVisible(groveTile.row)) {
                    val bCycle = floor(t / 13f).toInt()
                    if (bCycle % 3 == 2) {
                        val bu = (t % 13f) / 13f
                        val backTile = vis(groveTile)
                        val treeBase = Offset(iso.tileCenterX(backTile), iso.tileCenterY(backTile) + (if (worldMode) iso.tileH * .18f else -iso.tileH * .35f))
                        val treeH = iso.tileH * (2.6f + minOf(state.trunkTier, 15) * .06f)
                        val crown = Offset(treeBase.x + iso.tileW * .08f, treeBase.y - treeH * .80f)
                        val inFrom = crown + Offset(-iso.tileW * 4.5f, -iso.tileH * 2.5f)
                        val outTo = crown + Offset(iso.tileW * 4.5f, -iso.tileH * 3f)
                        val bPos: Offset; val bFlap: Float
                        when {
                            bu < .16f -> { val f = bu / .16f; bPos = lerp(inFrom, crown, f * f * (3f - 2f * f)); bFlap = sin(t * 5.2f * TAU) }
                            bu < .84f -> { bPos = crown + Offset(0f, sin((t / 2.6f) * TAU) * 1.6f); bFlap = .15f }
                            else -> { val f = (bu - .84f) / .16f; bPos = lerp(crown, outTo, f * f); bFlap = sin(t * 5.2f * TAU) }
                        }
                        bird(bPos, flap = bFlap)
                    }
                }
            }

            // ---- occasional falling leaf from trees and bushes (every ~11-17s per plant) ----
            if (animated) {
                visPlants.filter { it.archetype == Archetype.TREE || it.archetype == Archetype.BUSH }.forEach { pl ->
                    val period = 8f + pl.seed.mod(5)
                    val lt = (t + pl.seed.mod(100)) % period
                    if (lt < 2.4f) {
                        val p = lt / 2.4f
                        val v = vis(pl.tile)
                        val topY = iso.tileCenterY(v) + iso.tileH * .18f - tierHeight(iso.tileH, pl.sizeTier) * .8f
                        val lx = iso.tileCenterX(v) + sin(p * 5f + pl.seed) * iso.tileW * .10f + p * iso.tileW * .15f
                        val ly = topY + p * p * tierHeight(iso.tileH, pl.sizeTier) * .85f
                        rotate(degrees = p * 260f + pl.seed.mod(360), pivot = Offset(lx, ly)) {
                            drawOval(GardenPalette.leaf.copy(alpha = 1f - p * .6f), topLeft = Offset(lx - 5f, ly - 3f), size = Size(10f, 6f))
                        }
                    }
                }
                // every ~9s one plant glints, so even a quiet garden offers a small surprise
                if (visPlants.isNotEmpty()) {
                    val cycle = floor(t / 6.5f).toInt()
                    val sp = (t % 6.5f) / .9f
                    if (sp < 1f) {
                        val pl = visPlants[abs(cycle * 31) % visPlants.size]
                        val v = vis(pl.tile)
                        val gx = iso.tileCenterX(v) + iso.tileW * .08f
                        val gy = iso.tileCenterY(v) + iso.tileH * .18f - tierHeight(iso.tileH, pl.sizeTier) * .85f
                        sparkle(Offset(gx, gy), 8f, sin(sp * PI.toFloat()))
                    }
                }
            }

            // ---- butterflies: two ambient residents + dodge rewards, on lissajous loops ----
            repeat(if (animated) 2 + state.butterflies else state.butterflies) { i ->
                val tb = (t / 12f + i * .19f) % 1f
                val bx = size.width * .5f + size.width * .32f * sin(TAU * tb + i)
                val by = topReservePx + (size.height - topReservePx - bottomReservePx) * .3f +
                    60f * sin(2f * TAU * tb + i * 2f)
                butterfly(Offset(bx, by), flap = sin(t * 5f * TAU), sizePx = iso.tileW * .16f)
            }
        }

        // ================= GRADE: warm sunlight + cool depth, in screen space =================
        drawRect(
            Brush.radialGradient(
                listOf(Color(0x24FFDFA0), Color.Transparent),
                center = Offset(size.width * .85f, topReservePx * .38f),
                radius = size.width * .95f,
            )
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color(0x12103A5C)),
                startY = size.height * .55f, endY = size.height,
            )
        )

        // ================= LAYER: sea mist hugging the island base (nearest water, almost full parallax) =================
        withTransform({
            translate(cam.x * .9f, cam.y * .9f)
            scale(zoomNow, zoomNow, pivot = center)
        }) {
            if (!rowVisible(0)) return@withTransform
            val baseY = iso.tileCenterY(vis(Tile(0, state.gridCols - 1))) + iso.tileH * IsoMath.WALL_UNITS
            // 1C.6 Task 10: drought shore shamblers — a regretted-spending signal. When the
            // month is a drought (over budget), 1–2 mini-zombies wander the waterline below
            // the slab, never on tiles. The master clock freezes them when animations are off.
            if (worldMode && state.weather == Weather.DROUGHT) {
                repeat(2) { i ->
                    val walk = t / 40f + i * .5f
                    val sx = center.x + sin(walk * TAU) * size.width * .34f
                    val sy = baseY + iso.tileH * (.7f + i * .4f) + abs(sin(walk * TAU * 9f)) * iso.tileH * .06f
                    shoreShambler(Offset(sx, sy), iso.tileH * 1.2f)
                }
            }
            listOf(-.18f, .12f, .38f).forEachIndexed { i, dx ->
                val mx = size.width * (.5f + dx) + sin((t / 6.7f + i * .3f) * TAU) * 14f
                drawOval(GardenPalette.mist, topLeft = Offset(mx - 130f, baseY - 34f + i * 10f), size = Size(260f, 68f))
            }
        }
    }
}

/** Slow ambient camera drift so the world never sits perfectly still, even untouched. */
private fun idleDrift(t: Float, enabled: Boolean): Offset =
    if (!enabled) Offset.Zero
    else Offset(sin(t / 31f * TAU) * 10f, sin(t / 47f * TAU) * 7f)

private fun DrawScope.diamond(cx: Float, cy: Float, w: Float, h: Float, color: Color) {
    val p = Path().apply {
        moveTo(cx, cy - h / 2); lineTo(cx + w / 2, cy); lineTo(cx, cy + h / 2); lineTo(cx - w / 2, cy); close()
    }
    // pillow relief: lit at the far (top) edge, settling darker toward the near edge
    drawPath(
        p,
        Brush.verticalGradient(
            listOf(lerpColor(color, Color.White, .10f), lerpColor(color, Color(0xFF2E5B25), .08f)),
            startY = cy - h / 2, endY = cy + h / 2,
        ),
    )
    // soft dashed borders — FC's plot-path lines, garden flavored
    drawPath(p, Color(0x33FFFFFF), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
    // faint highlight along the two far edges sells the raised lip
    val hl = Path().apply { moveTo(cx - w / 2, cy); lineTo(cx, cy - h / 2); lineTo(cx + w / 2, cy) }
    drawPath(hl, Color(0x1AFFFFFF), style = Stroke(2.5f))
}

/** The center-island house, bottom-anchored at (cx, baseY) — the front corner of its 2×2 block. */
private fun DrawScope.house(bmp: ImageBitmap, cx: Float, baseY: Float, spanW: Float, shadow: Color, alpha: Float = 1f) {
    val hW = spanW
    val hH = hW * bmp.height / bmp.width
    drawOval(shadow.copy(alpha = shadow.alpha * alpha), topLeft = Offset(cx - hW * .42f, baseY - hH * .05f), size = Size(hW * .84f, hH * .13f))
    drawImage(
        image = bmp,
        srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset((cx - hW / 2f).toInt(), (baseY - hH).toInt()),
        dstSize = IntSize(hW.toInt(), hH.toInt()),
        alpha = alpha,
    )
}

/** 1C.6 Task 10: a mini regret-zombie shambling the waterline during a drought. Mirrors
 *  ProceduralPainter.zombie's shape (green body, X eyes, receipt hat) so it reads as kin to
 *  the risen purchases on the island, but lives off-tile in the water below the slab. */
private fun DrawScope.shoreShambler(a: Offset, h: Float) {
    drawOval(GardenPalette.shadow.copy(alpha = .18f), topLeft = Offset(a.x - h * .18f, a.y - h * .04f), size = Size(h * .36f, h * .10f))
    drawOval(Color(0xFF7E9464), topLeft = Offset(a.x - h * .16f, a.y - h * .52f), size = Size(h * .32f, h * .52f))
    drawCircle(Color(0xFF9DB07C), radius = h * .15f, center = Offset(a.x, a.y - h * .60f))
    listOf(-1f, 1f).forEach { s ->                       // drooping arms reaching forward
        drawLine(Color(0xFF7E9464), Offset(a.x + s * h * .14f, a.y - h * .40f),
            Offset(a.x + s * h * .26f, a.y - h * .22f), strokeWidth = h * .07f, cap = StrokeCap.Round)
    }
    listOf(-.06f, .06f).forEach { dx ->                  // X eyes
        val e = Offset(a.x + h * dx, a.y - h * .62f)
        drawLine(Color(0xFF3A2A1E), Offset(e.x - h * .025f, e.y - h * .025f), Offset(e.x + h * .025f, e.y + h * .025f), strokeWidth = h * .018f)
        drawLine(Color(0xFF3A2A1E), Offset(e.x - h * .025f, e.y + h * .025f), Offset(e.x + h * .025f, e.y - h * .025f), strokeWidth = h * .018f)
    }
    rotate(degrees = -14f, pivot = Offset(a.x, a.y - h * .72f)) {  // receipt hat
        drawRoundRect(Color(0xFFF4EFE2), topLeft = Offset(a.x - h * .09f, a.y - h * .84f), size = Size(h * .18f, h * .13f), cornerRadius = CornerRadius(h * .015f))
        drawLine(Color(0xFFC64545), Offset(a.x - h * .05f, a.y - h * .78f), Offset(a.x + h * .05f, a.y - h * .78f), strokeWidth = h * .015f)
    }
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

private fun DrawScope.bird(c: Offset, flap: Float) {
    // the classic two-arc gull glyph; wing tips rise and fall with the flap
    val w = 15f
    val lift = 7f + 4f * flap
    val p = Path().apply {
        moveTo(c.x - w, c.y)
        quadraticBezierTo(c.x - w / 2, c.y - lift, c.x, c.y)
        quadraticBezierTo(c.x + w / 2, c.y - lift, c.x + w, c.y)
    }
    drawPath(p, Color(0x99455A64), style = Stroke(3f, cap = StrokeCap.Round))
}

private fun DrawScope.bee(c: Offset, s: Float, flap: Float) {
    // wings first (behind), flickering with the flap
    val wa = .35f + .35f * abs(flap)
    drawOval(Color(0xB3FFFFFF).copy(alpha = wa), topLeft = Offset(c.x - s * 1.1f, c.y - s * 1.6f), size = Size(s * 1.0f, s * 1.2f))
    drawOval(Color(0xB3FFFFFF).copy(alpha = wa), topLeft = Offset(c.x + s * .1f, c.y - s * 1.6f), size = Size(s * 1.0f, s * 1.2f))
    // plump little body with two stripes
    drawOval(Color(0xFFF2B01F), topLeft = Offset(c.x - s, c.y - s * .7f), size = Size(s * 2f, s * 1.4f))
    drawLine(Color(0xFF44341B), Offset(c.x - s * .35f, c.y - s * .6f), Offset(c.x - s * .35f, c.y + s * .6f), strokeWidth = s * .38f)
    drawLine(Color(0xFF44341B), Offset(c.x + s * .3f, c.y - s * .55f), Offset(c.x + s * .3f, c.y + s * .55f), strokeWidth = s * .34f)
}

private fun DrawScope.dragonfly(c: Offset, s: Float, wingPhase: Float) {
    val stretch = .55f + .45f * abs(wingPhase)
    listOf(-1f, 1f).forEach { side ->
        drawOval(Color(0x8CBFEFFF), topLeft = Offset(c.x + side * s * .3f - s * 1.4f * stretch * (if (side < 0) 1f else 0f), c.y - s * .95f), size = Size(s * 1.4f * stretch, s * .5f))
        drawOval(Color(0x73BFEFFF), topLeft = Offset(c.x + side * s * .3f - s * 1.2f * stretch * (if (side < 0) 1f else 0f), c.y - s * .35f), size = Size(s * 1.2f * stretch, s * .45f))
    }
    drawLine(Color(0xFF2E8B9A), Offset(c.x - s * .2f, c.y), Offset(c.x + s * 1.9f, c.y + s * .12f), strokeWidth = s * .34f, cap = StrokeCap.Round)
    drawCircle(Color(0xFF236F7C), radius = s * .34f, center = Offset(c.x - s * .25f, c.y))
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
    val wing = sizePx * (0.4f + 0.6f * abs(flap))
    val h = sizePx * 1.3f
    drawOval(GardenPalette.butterflyA, topLeft = Offset(c.x - wing - sizePx * .1f, c.y - h / 2), size = Size(wing, h))
    drawOval(GardenPalette.butterflyB, topLeft = Offset(c.x + sizePx * .1f, c.y - h / 2), size = Size(wing, h))
    drawRoundRect(Color(0xFF3F3B52), topLeft = Offset(c.x - sizePx * .09f, c.y - h * .55f), size = Size(sizePx * .18f, h * 1.1f), cornerRadius = CornerRadius(sizePx * .09f))
}
