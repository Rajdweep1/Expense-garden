package com.expensegarden.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.capture.AwaitingPayment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AwaitingLogStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = context.getSharedPreferences("awaiting_log", Context.MODE_PRIVATE)

    @After fun teardown() { file.edit().clear().commit() }

    private fun p(id: String, handedOffAt: Long? = 2L, fixed: Long? = null) =
        AwaitingPayment(id, "shop@ybl", "Sharma Chai", "upi://pay?pa=shop@ybl&mc=5411", fixed, 1L, handedOffAt)

    @Test fun add_then_all_returns_it_with_every_field() {
        AwaitingLogStore(context).add(p("a", fixed = 5_000))
        assertEquals(listOf(p("a", fixed = 5_000)), AwaitingLogStore(context).all())
    }

    @Test fun nulls_survive_the_round_trip() {
        AwaitingLogStore(context).add(p("a", handedOffAt = null))
        assertEquals(listOf(p("a", handedOffAt = null)), AwaitingLogStore(context).all())
    }

    @Test fun remove_returns_and_deletes() {
        val store = AwaitingLogStore(context)
        store.add(p("a")); store.add(p("b"))
        assertEquals(p("a"), store.remove("a"))
        assertEquals(listOf("b"), AwaitingLogStore(context).all().map { it.id })
        assertNull(store.remove("a"))
    }

    @Test fun mark_shown_clears_only_that_marker() {
        val store = AwaitingLogStore(context)
        store.add(p("a")); store.add(p("b"))
        store.markShown("a")
        assertEquals(listOf(null, 2L), AwaitingLogStore(context).all().map { it.handedOffAt })
    }

    @Test fun restore_puts_it_back_without_its_marker() {
        val store = AwaitingLogStore(context)
        store.restore(p("a"))
        assertEquals(listOf(p("a", handedOffAt = null)), store.all())
    }

    @Test fun a_corrupt_file_reads_as_empty() {
        file.edit().putString("payments", "{not json").commit()
        assertTrue(AwaitingLogStore(context).all().isEmpty())
    }

    // His call 6: one awaiting payment at a time — a new hand-off makes any earlier one a home card,
    // so the form that opens on return is the new payment's (spec/AC review, 2026-11-14).
    @Test fun a_new_hand_off_clears_older_markers() {
        val store = AwaitingLogStore(context)
        store.add(p("old"))
        assertTrue(store.handOff(p("new").copy(scannedAt = 5L)))
        assertEquals(mapOf("old" to null, "new" to 2L), AwaitingLogStore(context).all().associate { it.id to it.handedOffAt })
    }
}
