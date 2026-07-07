package com.expensegarden.app.game

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.stats.CategoryTree
import com.expensegarden.app.stats.MonthStatsFolder
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The garden is a pure function of history. Same inputs → identical GardenState (jitter is uuid-seeded). */
object GardenFolder {
    fun fold(
        monthKey: String,
        monthTxns: List<TransactionEntity>,          // LOGGED, occurredAt inside the month
        categories: List<CategoryEntity>,
        budgets: List<BudgetEntity>,                 // that month's rows
        monthEvents: List<GameEventEntity>,          // createdAt inside the month
        allTimeInvestmentCount: Int,
        today: LocalDate,
        zone: ZoneId,
    ): GardenState {
        val ym = YearMonth.parse(monthKey)
        val archived = YearMonth.from(today) > ym
        val daysInMonth = ym.lengthOfMonth()
        // For archived months everything freezes at the final day; live months use today.
        val effectiveDay = if (archived) daysInMonth else today.dayOfMonth

        val tree = CategoryTree(categories)
        val ordered = monthTxns.sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        val mapped = ordered.mapNotNull { PlantMapper.map(it, tree) }
        val tiles = SerpentineTiler.tiles(mapped.size)
        val plants = mapped.mapIndexed { i, m ->
            Plant(m.txnUuid, m.archetype, m.sizeTier, m.isWeed, tiles[i], m.seed)
        }

        val leafSums = ordered.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val severity = MonthStatsFolder.fold(categories, leafSums, budgets, effectiveDay, daysInMonth).overallSeverity
        val weather = when (severity) {
            Severity.OK -> Weather.SUNNY
            Severity.PACE_WARNING -> Weather.OVERCAST
            Severity.BREACH -> Weather.DROUGHT
        }

        val dayTotals = ordered.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }
            .mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val overall = budgets.firstOrNull { it.categoryId == null }?.amountPaise
        val streakToday = if (archived) daysInMonth + 1 else today.dayOfMonth

        return GardenState(
            monthKey = monthKey,
            weather = weather,
            plants = plants,
            spentPaise = leafSums.values.sum(),
            backRowTreeCount = treeCount(allTimeInvestmentCount),
            trunkTier = allTimeInvestmentCount,
            butterflies = minOf(5, monthEvents.count { it.type == "gate.dodged" }),
            streakDays = StreakMath.underPaceStreak(dayTotals, overall, streakToday, daysInMonth),
            noSpendDays = StreakMath.noSpendDays(dayTotals, streakToday),
            archived = archived,
            gridRows = SerpentineTiler.gridRows(mapped.size),
            gridCols = SerpentineTiler.COLS,
        )
    }

    /** Tunable grove growth: 0 SIPs → no trees; then 1, 2 (≥10), 3 (≥25). Trunk thickens with every SIP. */
    private fun treeCount(sips: Int) = when {
        sips == 0 -> 0
        sips < 10 -> 1
        sips < 25 -> 2
        else -> 3
    }
}
