package com.expensegarden.app.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftStateTest {
    /** What the system hands back after process death: the same keys, in a fresh handle. */
    private fun afterProcessDeath(before: SavedStateHandle) =
        SavedStateHandle(before.keys().associateWith { before.get<Any?>(it) })

    @Test fun `a scanned draft and a manual one both survive saved state`() {
        val scanned = EntryDraft(
            fromScan = true, vpa = "shop@ybl", payeeName = "Sharma Chai", amountText = "1,200",
            categoryId = 103L, note = "masala", occurredAt = 1_760_000_000_000L, txnUuid = "uuid-1",
        )
        val manual = EntryDraft(payeeName = "Kirana", amountText = "250", occurredAt = 1_760_000_000_000L)
        for (draft in listOf(scanned, manual)) {
            val handle = SavedStateHandle()
            DraftState.save(handle, draft)
            assertEquals(draft, DraftState.restore(afterProcessDeath(handle)))
        }
    }

    @Test fun `nothing saved restores nothing`() = assertNull(DraftState.restore(SavedStateHandle()))
}
