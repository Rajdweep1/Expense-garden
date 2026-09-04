package com.expensegarden.app.data

import com.expensegarden.app.gate.Severity

class QuipRepository(private val db: AppDatabase) {
    /** Least-recently-used line for the severity; unused quips win first. */
    suspend fun pick(severity: Severity): String {
        // The bank is bucketed by tone from v3 on; threading the user's tone through is Task 9's
        // job, so pin the seeded sharp-but-fair bucket here to keep today's behaviour identical.
        val quip = db.quipDao().leastRecentlyUsed(severity.name, "SHARP")
            ?: return "Budget says no. You say...?"       // unreachable with seed bank; safe default
        db.quipDao().markUsed(quip.id, System.currentTimeMillis())
        return quip.text
    }
}
