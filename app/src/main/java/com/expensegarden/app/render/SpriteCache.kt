package com.expensegarden.app.render

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.ImageBitmap
import com.expensegarden.app.game.Archetype
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/** Plant sprites, decoded when the island first needs them and kept for the process lifetime.
 *
 *  The map is a [SnapshotStateMap], which is what makes this invisible to the renderer:
 *  `SpritePainter` reads it during the draw phase, Compose records that read, and writing a
 *  newly decoded sprite invalidates only the drawing — no recomposition, no explicit callback.
 *  Until a sprite lands the painter falls back to procedural art, which is the same path a
 *  partial sprite pack has always taken.
 *
 *  Replaces an eager decode of all 48 plant sprites (48 MB of ARGB_8888) that ran on the main
 *  thread at the first garden frame, because `MainActivity` called `.isEmpty()` on a `by lazy`
 *  map. Structures stay eager: a missing house sprite means no house, not a fallback.
 *
 *  Not an LRU. Sprites are evicted by process death alone, which is the honest trade: an island
 *  shows a bounded set, and re-decoding on scroll would trade a fixed cost for a recurring one. */
class SpriteCache(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val sprites: SnapshotStateMap<Pair<Archetype, Int>, ImageBitmap> = mutableStateMapOf()

    /** Keys already decoded or currently decoding. Guards against a second warm for the same
     *  key while the first is still on IO — without it, a state change during a decode would
     *  start a duplicate and pay for the same PNG twice. */
    private val claimed: MutableSet<Pair<Archetype, Int>> =
        Collections.synchronizedSet(mutableSetOf())

    /** Fire-and-forget warm for the canvas. */
    fun warm(keys: Set<Pair<Archetype, Int>>) {
        scope.launch { warmNow(keys) }
    }

    /** Suspending warm, so tests can await the decode instead of polling. */
    suspend fun warmNow(keys: Set<Pair<Archetype, Int>>) {
        val wanted = keys.filter { claimed.add(it) }
        if (wanted.isEmpty()) return
        val decoded = withContext(Dispatchers.IO) {
            wanted.mapNotNull { key ->
                SpriteLoader.decodePlant(context, key.first, key.second)?.let { key to it }
            }
        }
        // Publish on Main: the write is what invalidates the draw, and doing it on one thread
        // keeps the map's growth ordered with respect to the frames that read it.
        withContext(Dispatchers.Main) { sprites.putAll(decoded) }
        // A key with no asset stays claimed — re-asking every frame would re-open the asset
        // manager for a file that is not coming.
    }
}
