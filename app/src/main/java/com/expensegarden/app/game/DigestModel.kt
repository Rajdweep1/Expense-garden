package com.expensegarden.app.game

/** Persona intensity (spec §7). The boundaries do not relax at SAVAGE — only the tone does.
 *  Stored as `Tone.name` in AiPrefs and in the `quip.tone` column, so these names are a
 *  persisted contract: renaming one requires a migration. */
enum class Tone { SHARP, SAVAGE, GENTLE }

enum class DigestKind { DAILY, MONTHLY }

/** A typed projection of a `game_event` row (spec §5).
 *
 *  Not the entity itself: a month.closed row carries its month inside `payloadJson`, and a
 *  trigger taking raw entities would have to parse JSON to know which month it is speaking
 *  about — which would drag org.json into the pure core and break its JVM tests.
 *  DigestRepository parses at the boundary and hands this in. */
sealed interface DigestEvent {
    val id: Long
    data class MonthClosed(override val id: Long, val monthKey: String) : DigestEvent
    data class StreakHit(override val id: Long, val days: Int) : DigestEvent
    data class GateDodged(override val id: Long) : DigestEvent
    data class Regretted(override val id: Long) : DigestEvent
}

/** State at the moment the persona last spoke — the baseline for comparison triggers. */
data class DigestSnapshot(
    val weather: Weather,
    val houseLevel: Int,
    val streakDays: Int,
    val lastEventId: Long,
)

/** Month-scoped counts that `eventsSince` cannot see, because that window starts at the last
 *  digest. Without this, "first regret of the month" is not computable.
 *  `regretCount` = number of `transaction.regretted` events created inside `monthKey`, at
 *  evaluation time. `monthKey` is identity for traceability; the trigger does not read it. */
data class MonthFacts(val monthKey: String, val regretCount: Int)

sealed interface Trigger {
    data class WeatherChanged(val from: Weather, val to: Weather) : Trigger
    data class HouseLevelled(val from: Int, val to: Int) : Trigger
    data class StreakHit(val days: Int) : Trigger
    data object FirstRegretOfMonth : Trigger     // data: stable toString() for reasonJson
    /** A WIN. The user backed out at the payment gate; the persona celebrates it (spec §5).
     *  Carrying the polarity here means a writer cannot mistake it for a lapse. */
    data class GateDodged(val count: Int) : Trigger
    data class MonthClosed(val monthKey: String) : Trigger
}

data class DigestReason(
    val kind: DigestKind,
    val scopeKey: String,
    val triggers: List<Trigger>,
)

/** Never null — silence is `daily == null && monthly.isEmpty()`.
 *
 *  `monthly` is a LIST because the reconciler's outputs are plural: `Reconciler.decide`
 *  returns `monthsToClose: List<String>` and `runReconciler()` emits one event per element,
 *  so one open after a long gap can close four months at once. A single-reason return would
 *  speak about one and advance the watermark past the rest — permanently, because the
 *  reconciler's own idempotence filter never re-emits (spec §5). */
data class DigestVerdict(
    val daily: DigestReason?,
    val monthly: List<DigestReason>,
) {
    val isSilent: Boolean get() = daily == null && monthly.isEmpty()

    companion object { val SILENT = DigestVerdict(null, emptyList()) }
}
