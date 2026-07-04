package com.expensegarden.app.data

import com.expensegarden.app.gate.Severity

class QuipRepository(private val db: AppDatabase) {
    /** Least-recently-used line for the severity; unused quips win first. */
    suspend fun pick(severity: Severity): String {
        val quip = db.quipDao().leastRecentlyUsed(severity.name)
            ?: return "Budget says no. You say...?"       // unreachable with seed bank; safe default
        db.quipDao().markUsed(quip.id, System.currentTimeMillis())
        return quip.text
    }
}
