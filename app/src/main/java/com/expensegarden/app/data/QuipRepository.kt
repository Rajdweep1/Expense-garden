package com.expensegarden.app.data

import com.expensegarden.app.game.Tone
import com.expensegarden.app.gate.Severity

class QuipRepository(private val db: AppDatabase) {
    /** Least-recently-used line for this (severity, tone); unused quips win first.
     *
     *  Falls back to the seeded STATIC bank when the tone's bucket is empty — which is the
     *  normal state before any key is entered, and the reason the gate keeps working when
     *  every LLM call ever made fails (spec §6). The hardcoded default below it is the last
     *  resort and is unreachable while the seed bank exists. */
    suspend fun pick(severity: Severity, tone: Tone): String {
        val quip = db.quipDao().leastRecentlyUsed(severity.name, tone.name)
            ?: db.quipDao().leastRecentlyUsedStatic(severity.name)
            ?: return "Budget says no. You say...?"
        db.quipDao().markUsed(quip.id, System.currentTimeMillis())
        return quip.text
    }
}
