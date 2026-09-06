package com.expensegarden.app.data

import com.expensegarden.app.game.GardenFolder
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Reconciler
import com.expensegarden.app.game.StreakMath
import com.expensegarden.app.game.CollectionState
import com.expensegarden.app.game.RareCatalog
import com.expensegarden.app.game.RareEngine
import com.expensegarden.app.game.RarePairing
import com.expensegarden.app.game.RareSignal
import com.expensegarden.app.game.RareTier
import com.expensegarden.app.stats.CategoryTree
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Assembles fold inputs from Room and appends reconciler events. All game logic lives in game/ (pure). */
class GardenRepository(private val db: AppDatabase, private val ledger: LedgerRepository) {
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Live garden for the current month. Re-collection re-derives the month key (same idiom as the VMs). */
    fun observeCurrentGarden(): Flow<GardenState> {
        val monthKey = ledger.currentMonthKey()
        val (from, to) = ledger.boundsOfMonth(monthKey)
        return combine(
            db.transactionDao().observeLoggedBetween(from, to),
            db.categoryDao().observeAll(),
            db.budgetDao().observeAllForMonth(monthKey),
            db.gameEventDao().observeEventsBetween(from, to),
            db.transactionDao().observeLoggedCountIn(investmentIds()),
        ) { txns, cats, budgets, events, sips ->
            GardenFolder.fold(monthKey, txns, cats, budgets, events, sips, LocalDate.now(zone), zone)
        }
    }

    /** 1C.5: the persistent island — every LOGGED txn ever, with the current month's sky.
     *  1C.7: houseLevelOverride folds the same txns at a previous level, for the expansion. */
    fun observeAllTimeGarden(houseLevelOverride: Int? = null): Flow<GardenState> {
        val monthKey = ledger.currentMonthKey()
        val (from, to) = ledger.boundsOfMonth(monthKey)
        return combine(
            db.transactionDao().observeLoggedBetween(0L, Long.MAX_VALUE),
            db.categoryDao().observeAll(),
            db.budgetDao().observeAllForMonth(monthKey),
            // 4A needs the WHOLE log, not just this month: the once-ever guarantee in spec §3.3
            // can only be enforced by a fold that sees all of history. The current month's
            // slice is filtered back out below, so butterflies are unaffected.
            db.gameEventDao().observeEventsBetween(0L, Long.MAX_VALUE),
            db.transactionDao().observeLoggedCountIn(investmentIds()),
        ) { txns, cats, budgets, allEvents, sips ->
            GardenFolder.foldAllTime(
                txns, cats, budgets,
                allEvents.filter { it.createdAt in from..to },
                sips, LocalDate.now(zone), zone, houseLevelOverride,
                rareSignals = projectRareSignals(allEvents),
            )
        }
    }

    /** Turns raw rows into the typed signals [RareEngine] consumes (spec §4A).
     *
     *  This lives here, in the data layer, precisely because it touches `org.json` — the same
     *  boundary `DigestRepository.project` observes. Keeping it out of the `game` package is
     *  what lets the earning rules be unit-tested off-device at all.
     *
     *  A row whose payload will not parse is skipped rather than taking the whole fold down;
     *  one malformed event should cost you one trigger, not your entire garden. */
    private fun projectRareSignals(events: List<GameEventEntity>): List<RareSignal> =
        events.mapNotNull { e ->
            runCatching {
                when (e.type) {
                    "streak.hit" -> JSONObject(e.payloadJson).let { o ->
                        RareSignal.StreakHit(e.id, e.createdAt, o.getInt("days"), o.getString("month"))
                    }
                    "month.closed" -> JSONObject(e.payloadJson).let { o ->
                        RareSignal.MonthClosed(
                            e.id, e.createdAt, o.getString("month"),
                            o.getLong("spentPaise"),
                            // Absent or JSON null means no budget was set that month. It must
                            // stay null, never 0 — see RareEngine.
                            if (o.isNull("overallBudgetPaise")) null else o.getLong("overallBudgetPaise"),
                        )
                    }
                    "gate.dodged" -> RareSignal.GateDodged(e.id, e.createdAt)
                    "transaction.regret_cleared" ->
                        e.transactionUuid?.let { RareSignal.RegretCleared(e.id, e.createdAt, it) }
                    else -> null
                }
            }.getOrNull()
        }

    /** What the greenhouse album shows (spec §5).
     *
     *  "Found" means actually GROWN — a species that exists on the island — rather than merely
     *  earned. That distinction matters because a banked seed waits for a qualifying purchase,
     *  so claiming it before it has grown would show the user something they cannot go and look
     *  at. Landmarks are the exception: they are never plants, so an earned landmark counts the
     *  moment it is earned.
     *
     *  Derived from the same fold the island uses, so the album can never disagree with it. */
    suspend fun collection(): CollectionState {
        val garden = observeAllTimeGarden().first()

        val events = db.gameEventDao().eventsBetween(0L, Long.MAX_VALUE)
        val earns = RareEngine.earns(
            projectRareSignals(events),
            noSpendRunsByMonth(garden),
            breadthByMonth(garden),
            garden.houseLevel,
            zone,
        )
        // Re-derive the same assignment the island used — through GardenFolder's own candidate
        // builder, so the album cannot claim a species the garden does not actually show.
        val txns = db.transactionDao().loggedBetween(0L, Long.MAX_VALUE)
            .sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        val tree = CategoryTree(db.categoryDao().all())
        val awards = RarePairing.assign(earns, GardenFolder.rareCandidates(txns, tree))

        val foundBy = awards.values.associate { it.species.id to it.earn.trigger }
        val landmarks = RareCatalog.landmarkAssignment(earns).associate { (e, s) -> s.id to e.trigger }
        // Plantable earns still waiting for a purchase to land on.
        val pending = earns.count { it.tier != RareTier.LANDMARK } - awards.size
        return CollectionState(foundBy + landmarks, pending.coerceAtLeast(0))
    }

    /** The album re-derives these from the same transactions the fold saw, so it cannot drift
     *  from the island. Kept private because nothing outside the album needs them. */
    private suspend fun noSpendRunsByMonth(garden: GardenState): Map<String, Int> {
        val txns = db.transactionDao().loggedBetween(0L, Long.MAX_VALUE)
        val today = LocalDate.now(zone)
        val currentMonth = YearMonth.from(today)
        return txns.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) }
            .mapValues { (month, list) ->
                val spentDays = list.map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }.toSet()
                val elapsed = if (month == currentMonth) today.dayOfMonth - 1 else month.lengthOfMonth()
                var longest = 0
                var run = 0
                for (day in 1..elapsed) {
                    if (day in spentDays) run = 0 else run++
                    if (run > longest) longest = run
                }
                longest
            }
            .mapKeys { it.key.toString() }
    }

    private suspend fun breadthByMonth(garden: GardenState): Map<String, Int> {
        val txns = db.transactionDao().loggedBetween(0L, Long.MAX_VALUE)
        val tree = CategoryTree(db.categoryDao().all())
        return txns.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)).toString() }
            .mapValues { (_, list) -> list.mapNotNull { tree.ancestorChain(it.categoryId).lastOrNull() }.distinct().size }
    }

    suspend fun foldMonth(monthKey: String): GardenState {
        val (from, to) = ledger.boundsOfMonth(monthKey)
        val cats = db.categoryDao().all()
        return GardenFolder.fold(
            monthKey,
            db.transactionDao().loggedBetween(from, to),
            cats,
            db.budgetDao().allForMonth(monthKey),
            db.gameEventDao().eventsBetween(from, to),
            allTimeInvestmentCount = db.transactionDao().loggedBetween(0L, Long.MAX_VALUE)
                .count { it.categoryId in investmentIds() },
            today = LocalDate.now(zone),
            zone = zone,
        )
    }

    /** Months that have any LOGGED data, oldest first, current month included. */
    suspend fun monthsWithData(): List<String> {
        val earliest = db.transactionDao().earliestLoggedAt() ?: return emptyList()
        val start = YearMonth.from(Instant.ofEpochMilli(earliest).atZone(zone))
        val now = YearMonth.now(zone)
        // toList() before filter: List.filter is inline (suspend call OK); Sequence.filter is not.
        return generateSequence(start) { if (it < now) it.plusMonths(1) else null }
            .map { it.toString() }
            .toList()
            .filter { key ->
                if (key == now.toString()) return@filter true   // live month always listed, even if empty
                val (f, t) = ledger.boundsOfMonth(key)
                db.transactionDao().loggedBetween(f, t).isNotEmpty()
            }
    }

    /** On-open reconciler: append month.closed for elapsed months and streak.hit thresholds, idempotently. */
    suspend fun runReconciler() {
        val nowMonth = YearMonth.now(zone)
        val closed = db.gameEventDao().ofType("month.closed")
            .mapNotNull { runCatching { JSONObject(it.payloadJson).getString("month") }.getOrNull() }
            .toSet()
        val monthKey = nowMonth.toString()
        val (from, to) = ledger.boundsOfMonth(monthKey)
        val monthTxns = db.transactionDao().loggedBetween(from, to)
        val dayTotals = monthTxns.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }
            .mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val overall = db.budgetDao().allForMonth(monthKey).firstOrNull { it.categoryId == null }?.amountPaise
        val today = LocalDate.now(zone)
        val streak = StreakMath.underPaceStreak(dayTotals, overall, today.dayOfMonth, nowMonth.lengthOfMonth())
        val hitAlready = db.gameEventDao().ofType("streak.hit")
            .mapNotNull { runCatching { JSONObject(it.payloadJson) }.getOrNull() }
            .filter { it.optString("month") == monthKey }
            .map { it.getInt("days") }
            .toSet()

        val decisions = Reconciler.decide(
            currentMonth = nowMonth,
            monthsWithData = monthsWithData().map { YearMonth.parse(it) },
            closedMonths = closed,
            currentStreakDays = streak,
            streakHitDaysThisMonth = hitAlready,
        )

        for (m in decisions.monthsToClose) {
            val (f, t) = ledger.boundsOfMonth(m)
            val spent = db.transactionDao().loggedSumBetween(f, t)
            val budget = db.budgetDao().allForMonth(m).firstOrNull { it.categoryId == null }?.amountPaise
            val payload = JSONObject().put("month", m).put("spentPaise", spent)
                .put("overallBudgetPaise", budget ?: JSONObject.NULL)
            db.gameEventDao().insert(GameEventEntity(
                type = "month.closed", payloadJson = payload.toString(), transactionUuid = null,
                createdAt = System.currentTimeMillis(),
            ))
        }
        for (days in decisions.streakHitsToEmit) {
            val payload = JSONObject().put("month", monthKey).put("days", days)
            db.gameEventDao().insert(GameEventEntity(
                type = "streak.hit", payloadJson = payload.toString(), transactionUuid = null,
                createdAt = System.currentTimeMillis(),
            ))
        }
    }

    private fun investmentIds(): List<Long> = listOf(10L)   // Investments subtree (no children in seed; revisit at 1E import mapping)
}
