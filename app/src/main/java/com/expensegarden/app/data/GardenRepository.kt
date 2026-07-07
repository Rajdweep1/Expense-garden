package com.expensegarden.app.data

import com.expensegarden.app.game.GardenFolder
import com.expensegarden.app.game.GardenState
import com.expensegarden.app.game.Reconciler
import com.expensegarden.app.game.StreakMath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
