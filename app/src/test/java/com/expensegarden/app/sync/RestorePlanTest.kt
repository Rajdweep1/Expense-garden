package com.expensegarden.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RestorePlanTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun `an empty server is never restored over a ledger`() {
        val plan = RestorePlan.of(phoneTxns = 42, unsynced = 0, serverTxns = 0, serverLatestAt = null, zone = zone)
        assertTrue(plan is RestorePlan.Refuse)
        assertEquals("The server has no payments. Restoring would erase this phone's 42 payments, so it won't.", plan.message)
    }

    @Test fun `the restore warning counts what isn't backed up yet`() {
        val at = LocalDate.of(2026, 11, 8).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = RestorePlan.of(phoneTxns = 42, unsynced = 3, serverTxns = 40, serverLatestAt = at, zone = zone)
        assertTrue(plan is RestorePlan.Confirm)
        assertEquals(
            "The server has 40 payments, the latest on 8 Nov 2026. This phone has 42, and 3 changes " +
                "aren't backed up yet — they will be lost. Restoring replaces this phone's ledger and garden.",
            plan.message,
        )
        // An empty phone is the normal restore: nothing to lose, so no warning clause.
        assertEquals(
            "The server has 40 payments, the latest on 8 Nov 2026. This phone has none. Restoring replaces " +
                "this phone's ledger and garden.",
            RestorePlan.of(0, 0, 40, at, zone).message,
        )
    }

    @Test fun `restore copy counts in singulars`() {
        assertEquals(
            "The server has 1 payment. This phone has 1, and 1 change isn't backed up yet — it will be lost. " +
                "Restoring replaces this phone's ledger and garden.",
            RestorePlan.of(phoneTxns = 1, unsynced = 1, serverTxns = 1, serverLatestAt = null, zone = zone).message,
        )
        assertEquals(
            "The server has no payments. Restoring would erase this phone's 1 payment, so it won't.",
            RestorePlan.of(1, 0, 0, null, zone).message,
        )
    }
}
