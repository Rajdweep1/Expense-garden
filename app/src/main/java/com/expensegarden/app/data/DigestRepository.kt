package com.expensegarden.app.data

import com.expensegarden.app.game.DigestEvent
import com.expensegarden.app.game.DigestKind
import com.expensegarden.app.game.DigestReason
import com.expensegarden.app.game.DigestSnapshot
import com.expensegarden.app.game.MonthFacts
import com.expensegarden.app.game.Weather
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

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

    /** Projects raw rows into the typed events DigestTrigger consumes.
     *
     *  `floorMillis` is the first-run floor (spec §5): with no previous digest there is no
     *  lower bound, and `game_event` is never pruned, so an unfloored read would sweep up
     *  every event since 1A. A row whose payload will not parse is skipped — one bad row must
     *  not take the whole fold down. */
    suspend fun eventsSince(afterId: Long, floorMillis: Long?): List<DigestEvent> =
        db.gameEventDao().eventsAfterId(afterId)
            .filter { floorMillis == null || it.createdAt >= floorMillis }
            .mapNotNull { row ->
                runCatching {
                    when (row.type) {
                        "month.closed" ->
                            DigestEvent.MonthClosed(row.id, JSONObject(row.payloadJson).getString("month"))
                        "streak.hit" ->
                            DigestEvent.StreakHit(row.id, JSONObject(row.payloadJson).getInt("days"))
                        "gate.dodged" -> DigestEvent.GateDodged(row.id)
                        "transaction.regretted" -> DigestEvent.Regretted(row.id)
                        else -> null
                    }
                }.getOrNull()
            }

    /** Month-scoped counts the id window cannot see (spec §5). */
    suspend fun monthFacts(monthKey: String): MonthFacts {
        val (from, to) = ledger.boundsOfMonth(monthKey)
        val regrets = db.gameEventDao().eventsBetween(from, to)
            .count { it.type == "transaction.regretted" }
        return MonthFacts(monthKey, regrets)
    }

    /** The highest id currently in the log — captured BEFORE the LLM call, so an event
     *  logged during the round trip stays pending rather than falling behind the watermark. */
    suspend fun currentHeadId(): Long = db.gameEventDao().eventsAfterId(0L).lastOrNull()?.id ?: 0L

    suspend fun write(
        reason: DigestReason,
        text: String,
        snapshot: DigestSnapshot,
        nowMillis: Long,
    ) {
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
    }

    fun observeDaily(day: String): Flow<DigestEntity?> = db.digestDao().observeDaily(day)

    suspend fun monthly(monthKey: String): DigestEntity? = db.digestDao().monthly(monthKey)

    suspend fun dismiss(id: Long, nowMillis: Long) = db.digestDao().dismiss(id, nowMillis)

}
