package com.expensegarden.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockTest {
    private class FakeStore(override var lastStamp: Long = 0L) : SyncClock.Store

    @Test fun `stamps follow the wall clock when it moves forward`() {
        var now = 1_000L
        val clock = SyncClock({ now }, FakeStore())
        assertEquals(1_000L, clock.next())
        now = 2_000L
        assertEquals(2_000L, clock.next())
    }

    @Test fun `two stamps in the same millisecond are still strictly increasing`() {
        val clock = SyncClock({ 5_000L }, FakeStore())
        assertEquals(5_000L, clock.next())
        assertEquals(5_001L, clock.next())
        assertEquals(5_002L, clock.next())
    }

    @Test fun `a backwards clock jump cannot produce a smaller stamp`() {
        var now = 9_000L
        val clock = SyncClock({ now }, FakeStore())
        assertEquals(9_000L, clock.next())
        now = 3_000L                       // NTP correction, timezone edit, manual change
        assertEquals(9_001L, clock.next())
    }

    @Test fun `the stamp survives a restart because it is read back from the store`() {
        val store = FakeStore()
        SyncClock({ 4_000L }, store).next()
        val afterRestart = SyncClock({ 1_000L }, store)   // clock wrong on boot
        assertTrue(afterRestart.next() > 4_000L)
    }
}
