package com.expensegarden.app.ui

import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TxnRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PendingLabelsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val row = TxnRow(
        uuid = "u1", amountPaise = 20_000, payeeName = "Sharma Chai", categoryName = "Chai & Snacks",
        categoryId = 103, regret = Regret.UNRATED,
        occurredAt = LocalDateTime.parse("2026-10-18T14:16").atZone(zone).toInstant().toEpochMilli(),
    )

    @Test fun `the question names the amount and the payee`() =
        assertEquals("Did ₹200 to Sharma Chai go through?", PendingLabels.question(row))

    @Test fun `the detail gives the time, and the queue only when there is one`() {
        assertEquals("18 Oct, 14:16", PendingLabels.detail(row, total = 1, zone = zone))
        assertEquals("18 Oct, 14:16 · 1 of 3", PendingLabels.detail(row, total = 3, zone = zone))
    }

    @Test fun `undo messages say what happened to which payment`() {
        assertEquals("Logged ₹200 to Sharma Chai", PendingLabels.logged(20_000, "Sharma Chai"))
        assertEquals("Discarded ₹200 to Sharma Chai", PendingLabels.discarded(20_000, "Sharma Chai"))
    }
}
