package com.expensegarden.app.ai

import com.expensegarden.app.data.AppDatabase
import com.expensegarden.app.data.QuipEntity
import com.expensegarden.app.game.Tone

/** Tops up depleted (severity × tone) buckets (spec §6).
 *
 *  Only the tone the user is currently on gets refreshed — refreshing all three would triple
 *  the call volume to keep two banks nobody is reading stocked.
 *
 *  The seeded STATIC quips are never deleted; LLM lines augment the bank rather than
 *  replacing it. That is what guarantees the gate has content even if every call ever made
 *  fails.
 *
 *  Once-a-day throttling is the CALLER's (spec §4): it reads and stamps
 *  `AiPrefs.lastQuipRefreshAt` before calling [refresh]. This class checks only bucket stock. */
class QuipRefresher(private val llm: LlmClient, private val sink: Sink) {

    /** The seam that keeps this class unit-testable without Room. */
    interface Sink {
        suspend fun unusedCount(severity: String, tone: Tone): Int
        suspend fun insert(severity: String, tone: Tone, lines: List<String>)
    }

    suspend fun refresh(tone: Tone, severities: List<String> = DEFAULT_SEVERITIES) {
        for (severity in severities) {
            if (sink.unusedCount(severity, tone) >= LOW_STOCK) continue
            val raw = llm.complete(Persona.quipPrompt(tone, severity, ASK_FOR)) ?: continue
            val clean = QuipSanitizer.cleanAll(raw)
            if (clean.isNotEmpty()) sink.insert(severity, tone, clean)
        }
    }

    companion object {
        const val LOW_STOCK = 5
        const val ASK_FOR = 8
        val DEFAULT_SEVERITIES = listOf("PACE_WARNING", "BREACH")
    }
}

/** The production Sink. Kept next to the class it serves rather than in the data package,
 *  because it exists only to adapt Room to QuipRefresher's seam. */
class RoomQuipSink(private val db: AppDatabase) : QuipRefresher.Sink {
    override suspend fun unusedCount(severity: String, tone: Tone): Int =
        db.quipDao().unusedCount(severity, tone.name)

    override suspend fun insert(severity: String, tone: Tone, lines: List<String>) =
        db.quipDao().insertAll(
            lines.map {
                QuipEntity(
                    severity = severity, origin = "LLM", tone = tone.name,
                    text = it, usedAt = null,
                )
            }
        )
}
