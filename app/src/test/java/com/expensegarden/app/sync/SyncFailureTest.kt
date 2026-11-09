package com.expensegarden.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureTest {
    @Test fun `each failure gets a name`() {
        assertEquals(SyncFailure.UNAUTHORIZED, SyncFailure.classify(401, null))
        assertEquals(SyncFailure.UNAUTHORIZED, SyncFailure.classify(403, null))
        assertEquals(SyncFailure.NOT_FOUND, SyncFailure.classify(404, null))
        assertEquals(SyncFailure.SERVER, SyncFailure.classify(500, null))
        assertEquals(SyncFailure.UNREACHABLE, SyncFailure.classify(null, java.net.ConnectException()))
        assertEquals(SyncFailure.UNREACHABLE, SyncFailure.classify(null, java.net.SocketTimeoutException()))
        assertEquals(SyncFailure.UNREACHABLE, SyncFailure.classify(null, java.net.UnknownHostException()))
        assertEquals(SyncFailure.CLEARTEXT_BLOCKED, SyncFailure.classify(null, java.net.UnknownServiceException()))
        assertEquals(SyncFailure.BAD_URL, SyncFailure.classify(null, java.net.MalformedURLException()))
    }

    @Test fun `the status names the last failure only while it is current`() {
        val now = 10 * 3_600_000L
        assertEquals("Backed up 2h ago · last try failed: token rejected, 1h ago",
            syncStatusLine(true, now - 2 * 3_600_000, SyncFailure.UNAUTHORIZED, now - 3_600_000, 0, now))
        assertEquals("Backed up 1h ago",
            syncStatusLine(true, now - 3_600_000, SyncFailure.UNAUTHORIZED, now - 2 * 3_600_000, 0, now))
        assertEquals("Never backed up · 3 waiting · last try failed: can't reach the server, just now",
            syncStatusLine(true, 0L, SyncFailure.UNREACHABLE, now - 1_000, 3, now))
    }

    @Test fun `an unconfigured backup is off, not broken`() {
        assertEquals("Backup off", syncStatusLine(false, 0L, null, 0L, 5, 10_000L))
    }
}
