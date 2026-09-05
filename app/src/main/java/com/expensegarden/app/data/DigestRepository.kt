package com.expensegarden.app.data

import com.expensegarden.app.game.DigestEvent
import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.DigestSnapshot
import com.expensegarden.app.game.MonthFacts
import com.expensegarden.app.game.Weather
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** One evaluation's worth of the log. `head` is the watermark every digest row in this job will
 *  carry; `events` is everything strictly after the previous watermark, up to and including it. */
data class EventWindow(val head: Long, val events: List<DigestEvent>)

/** Owns the `digest` table and every JSON parse in the digest path (spec §3).
 *
 *  DigestTrigger is a pure fold with no org.json, which is what makes the entire silence
 *  rule JVM-testable — the unit-test source set has no `returnDefaultValues`, so org.json
 *  throws "not mocked" there. That constraint and the design agree: the decision to speak is
 *  testable, only the wording is not. */
class DigestRepository(private val db: AppDatabase, private val ledger: LedgerRepository) {

    /** The watermark row's state — the baseline for the next evaluation's comparisons. */
    suspend fun latestSnapshot(): DigestSnapshot? {
        val row = db.digestDao().latest() ?: return null
        return runCatching {
            val o = JSONObject(row.snapshotJson)
            DigestSnapshot(
                weather = Weather.valueOf(o.getString("weather")),
                houseLevel = o.getInt("houseLevel"),
                streakDays = o.getInt("streakDays"),
                lastEventId = row.lastEventId,
            )
        }.getOrNull()
    }

    /** Reads the head FIRST, then the events up to it (spec §9). An event inserted between the
     *  two reads has id > head, stays above the watermark, and is seen next time — that
     *  ordering is what closes the race.
     *
     *  The first-run floor lives here so a caller cannot forget it: with no previous digest
     *  there is no lower id bound, and `game_event` is never pruned, so the window is floored
     *  at `todayStartMillis` rather than sweeping up every event since 1A. A row whose payload
     *  will not parse is skipped — one bad row must not take the whole fold down. */
    suspend fun window(lastDigest: DigestSnapshot?, todayStartMillis: Long): EventWindow {
        val head = db.gameEventDao().headId()
        val afterId = lastDigest?.lastEventId ?: 0L
        val floor = if (lastDigest == null) todayStartMillis else null
        val events = db.gameEventDao().eventsInIdRange(afterId, head)
            .filter { floor == null || it.createdAt >= floor }
            .mapNotNull(::project)
        return EventWindow(head, events)
    }

    private fun project(row: GameEventEntity): DigestEvent? = runCatching {
        when (row.type) {
            "month.closed" -> DigestEvent.MonthClosed(row.id, JSONObject(row.payloadJson).getString("month"))
            "streak.hit" -> DigestEvent.StreakHit(row.id, JSONObject(row.payloadJson).getInt("days"))
            "gate.dodged" -> DigestEvent.GateDodged(row.id)
            "transaction.regretted" -> DigestEvent.Regretted(row.id)
            else -> null
        }
    }.getOrNull()

    /** Month-scoped counts the id window cannot see (spec §5). */
    suspend fun monthFacts(monthKey: String): MonthFacts {
        val (from, to) = ledger.boundsOfMonth(monthKey)
        val regrets = db.gameEventDao().eventsBetween(from, to)
            .count { it.type == "transaction.regretted" }
        return MonthFacts(monthKey, regrets)
    }

    /** Returns the new rowId, or -1 when `UNIQUE(kind, scopeKey)` made the insert a no-op. */
    suspend fun write(
        reason: DigestReason,
        text: String,
        snapshot: DigestSnapshot,
        nowMillis: Long,
    ): Long =
        db.digestDao().insert(
            DigestEntity(
                kind = reason.kind.name,
                scopeKey = reason.scopeKey,
                text = text,
                reasonJson = JSONObject()
                    .put("triggers", JSONArray(reason.triggers.map { it.toString() }))
                    .toString(),
                snapshotJson = JSONObject()
                    .put("weather", snapshot.weather.name)
                    .put("houseLevel", snapshot.houseLevel)
                    .put("streakDays", snapshot.streakDays)
                    .toString(),
                lastEventId = snapshot.lastEventId,
                createdAt = nowMillis,
                dismissedAt = null,
            )
        )

    /** All or nothing (spec §9). Every row in a job carries the same `lastEventId`, so writing
     *  some reasons and not others would consume the failed reasons' events forever. The job
     *  composes every text first and only then calls this; one transaction also means a
     *  process death mid-write cannot leave half a job behind.
     *
     *  Returns false when nothing was written because a reason's scope already had a row.
     *  `UNIQUE(kind, scopeKey)` turns that insert into a silent no-op, and committing its
     *  siblings would carry the shared watermark past events nobody ever spoke about — the
     *  partial-write loss again, arriving through the constraint instead of through a failed
     *  call. Rolling back leaves the whole verdict pending for the next open. */
    suspend fun writeAll(
        entries: List<Pair<DigestReason, String>>,
        snapshot: DigestSnapshot,
        nowMillis: Long,
    ): Boolean = try {
        db.withTransaction {
            entries.forEach { (reason, text) ->
                if (write(reason, text, snapshot, nowMillis) == NO_ROW) throw ScopeTaken()
            }
        }
        true
    } catch (e: ScopeTaken) {
        false
    }

    /** Has this exact scope already been spoken? The job asks before composing, so a repeat
     *  scope costs no completion — [writeAll]'s rollback is the guard behind this check, for
     *  the window between asking and writing. */
    suspend fun exists(reason: DigestReason): Boolean =
        db.digestDao().byScope(reason.kind.name, reason.scopeKey) != null

    /** Control flow, not an error: unwinding is how a Room transaction is rolled back. Stackless
     *  because it never escapes [writeAll] and is never logged. */
    private class ScopeTaken : Exception(null, null, false, false)

    fun observeDaily(day: String): Flow<DigestEntity?> = db.digestDao().observeDaily(day)

    suspend fun monthly(monthKey: String): DigestEntity? = db.digestDao().monthly(monthKey)

    suspend fun dismiss(id: Long, nowMillis: Long) = db.digestDao().dismiss(id, nowMillis)

    private companion object {
        /** Room's @Insert returns this when OnConflictStrategy.IGNORE swallows the row. */
        const val NO_ROW = -1L
    }
}
