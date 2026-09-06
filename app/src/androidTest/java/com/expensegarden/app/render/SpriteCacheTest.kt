package com.expensegarden.app.render

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.game.Archetype
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpriteCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun cache() = SpriteCache(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test fun a_new_cache_has_decoded_nothing() {
        assertTrue("construction must not decode", cache().sprites.isEmpty())
    }

    @Test fun warming_a_key_decodes_exactly_that_key() = runBlocking {
        val c = cache()
        val key = SpriteLoader.availableKeys(context).first { it.first == Archetype.TREE }
        c.warmNow(setOf(key))
        assertNotNull("TREE sprite should be resident", c.sprites[key])
        assertEquals("nothing else should have been decoded", 1, c.sprites.size)
    }

    @Test fun warming_the_same_key_twice_decodes_once() = runBlocking {
        val c = cache()
        val key = SpriteLoader.availableKeys(context).first { it.first == Archetype.TREE }
        c.warmNow(setOf(key))
        val first = c.sprites[key]
        c.warmNow(setOf(key))
        assertTrue("a resident sprite must not be re-decoded", first === c.sprites[key])
    }

    @Test fun a_key_with_no_asset_is_skipped_without_throwing() = runBlocking {
        val c = cache()
        c.warmNow(setOf(Archetype.TREE to SpriteLoader.MAX_VARIANTS + 5))
        assertTrue("an absent sprite is a fallback, not an error", c.sprites.isEmpty())
    }
}
