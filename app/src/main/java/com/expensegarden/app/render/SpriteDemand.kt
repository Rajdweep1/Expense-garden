package com.expensegarden.app.render

import com.expensegarden.app.game.Archetype
import com.expensegarden.app.game.GardenState

/** Which sprites an island actually needs, derived from state rather than observed during draw.
 *
 *  Deriving demand from [GardenState] keeps every side effect out of the draw phase: the canvas
 *  warms this set in a LaunchedEffect, and the draw pass only ever reads. A cache filled from
 *  inside `drawPlant` would mutate snapshot state during drawing, which Compose treats as a bug.
 *
 *  Pure and Android-free on purpose — this is the part worth unit-testing, and BitmapFactory
 *  throws "not mocked" under JVM tests. */
object SpriteDemand {

    fun of(state: GardenState): Set<Pair<Archetype, Int>> {
        val keys = mutableSetOf<Pair<Archetype, Int>>()
        // The back-row grove and the house-yard grove both synthesise TREE plants that never
        // appear in state.plants (GardenCanvas.kt:378 and :555). Always warming it costs one
        // sprite and avoids a procedural tree flashing on every island that has one.
        keys += Archetype.TREE to 0
        for (plant in state.plants) {
            keys += plant.archetype to plant.variant
            // SpritePainter resolves `archetype to variant` ?: `archetype to 0`, so the base
            // look is on the render path for every plant whose own variant has not landed yet.
            keys += plant.archetype to 0
        }
        return keys
    }
}
