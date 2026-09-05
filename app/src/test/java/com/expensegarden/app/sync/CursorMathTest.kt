package com.expensegarden.app.sync

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.SyncTombstoneEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorMathTest {
    private fun budget(stamp: Long) =
        BudgetEntity(categoryId = 1, month = "2026-09", amountPaise = 1, updatedAt = stamp)

    private fun event(id: Long) =
        GameEventEntity(id = id, type = "gate.dodged", payloadJson = "{}", transactionUuid = null, createdAt = 0L)

    private fun batch(
        budgets: List<BudgetEntity> = emptyList(),
        tombstones: List<SyncTombstoneEntity> = emptyList(),
        events: List<GameEventEntity> = emptyList(),
    ) = SyncBatch(emptyList(), emptyList(), emptyList(), budgets, tombstones, events)

    @Test fun `an empty batch leaves the cursors untouched`() {
        val before = Cursors(lastPushedAt = 50L, lastPushedEventId = 7L)
        assertEquals(before, CursorMath.advanced(before, batch()))
    }

    @Test fun `the row cursor advances to the highest stamp in the batch`() {
        val after = CursorMath.advanced(Cursors(0L, 0L), batch(budgets = listOf(budget(10), budget(30), budget(20))))
        assertEquals(30L, after.lastPushedAt)
    }

    @Test fun `a tombstone deletedAt counts toward the row cursor`() {
        // Otherwise a batch of pure deletions would advance nothing and resend forever.
        val after = CursorMath.advanced(
            Cursors(0L, 0L),
            batch(tombstones = listOf(SyncTombstoneEntity("budget", "*|2026-09", 99L))),
        )
        assertEquals(99L, after.lastPushedAt)
    }

    @Test fun `the event cursor advances to the highest id`() {
        val after = CursorMath.advanced(Cursors(0L, 0L), batch(events = listOf(event(3), event(9), event(5))))
        assertEquals(9L, after.lastPushedEventId)
    }

    @Test fun `cursors never regress`() {
        val before = Cursors(lastPushedAt = 100L, lastPushedEventId = 40L)
        val after = CursorMath.advanced(before, batch(budgets = listOf(budget(5)), events = listOf(event(2))))
        assertTrue(after.lastPushedAt >= before.lastPushedAt)
        assertTrue(after.lastPushedEventId >= before.lastPushedEventId)
    }

    @Test fun `emptiness and row count are reported from every list`() {
        assertTrue(batch().isEmpty)
        assertFalse(batch(events = listOf(event(1))).isEmpty)
        assertEquals(2, batch(budgets = listOf(budget(1)), events = listOf(event(1))).rowCount)
        assertEquals(1, batch(tombstones = listOf(SyncTombstoneEntity("budget", "*|2026-09", 1L))).rowCount)
    }
}
