package com.expensegarden.app.sync

/** The app-wide logical clock every `updatedAt` comes from (spec §2.3).
 *
 *  Two jobs, both of which a bare System.currentTimeMillis() fails at:
 *
 *  1. A phone whose clock jumps backwards — NTP correction, timezone edit, manual change —
 *     must not produce a stamp that makes a newer row look older and lose a last-write-wins
 *     comparison on the server.
 *  2. Two rows written inside the same millisecond must not share a stamp. The dirty-row
 *     predicate is `updatedAt > lastPushedAt`, so tied stamps straddling a batch boundary
 *     would leave the second row permanently unpushed.
 *
 *  Point 2 is the defect class 1D hit twice: `runReconciler` stamps a whole batch of events
 *  with one currentTimeMillis(), so timestamps collide and cannot order the batch. This is
 *  what makes the spec's chosen `updated_at` cursor sound rather than merely conventional. */
class SyncClock(
    private val now: () -> Long,
    private val store: Store,
) {
    /** Persistence seam. Production passes SyncPrefs; tests pass a field holder. */
    interface Store {
        var lastStamp: Long
    }

    @Synchronized
    fun next(): Long {
        val stamp = maxOf(now(), store.lastStamp + 1)
        store.lastStamp = stamp
        return stamp
    }

    companion object {
        /** A non-persisting clock, for tests and for constructors that predate the container.
         *  Production must pass the SyncPrefs-backed one: an in-memory stamp restarts from the
         *  wall clock on every launch, which is harmless for a test that lives milliseconds
         *  and wrong for a device that has to keep syncing across restarts. */
        fun inMemory(): SyncClock =
            SyncClock({ System.currentTimeMillis() }, object : Store { override var lastStamp = 0L })
    }
}
