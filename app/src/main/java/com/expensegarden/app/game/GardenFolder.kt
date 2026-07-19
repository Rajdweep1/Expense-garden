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
            Plant(m.txnUuid, m.archetype, m.sizeTier, m.isWeed, tiles[i], m.seed, m.variant)
        }

        val leafSums = ordered.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val severity = MonthStatsFolder.fold(categories, leafSums, budgets, effectiveDay, daysInMonth).overallSeverity
        val weather = weatherOf(severity)

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

    /** 1C.5: the persistent island. Every LOGGED txn ever stands in one chronological
     *  serpentine field that only grows; the sky (weather/spend/streaks/butterflies)
     *  still reads the CURRENT month, so the land remembers while the mood is live. */
    fun foldAllTime(
        allTxns: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        currentBudgets: List<BudgetEntity>,                  // current month's rows
        currentMonthEvents: List<GameEventEntity>,           // createdAt inside the current month
        allTimeInvestmentCount: Int,
        today: LocalDate,
        zone: ZoneId,
    ): GardenState {
        val ym = YearMonth.from(today)
        val daysInMonth = ym.lengthOfMonth()

        val tree = CategoryTree(categories)
        val ordered = allTxns.sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        // Pair each planted txn with its month — markers fall out of the same chronological pass.
        val mapped = ordered.mapNotNull { t ->
            PlantMapper.map(t, tree)?.let { m ->
                m to YearMonth.from(Instant.ofEpochMilli(t.occurredAt).atZone(zone)).toString()
            }
        }
        val tiles = SpiralTiler.tiles(mapped.size)
        val plants = mapped.mapIndexed { i, (m, _) -> Plant(m.txnUuid, m.archetype, m.sizeTier, m.isWeed, tiles[i], m.seed, m.variant) }
        val markers = mapped.mapIndexedNotNull { i, (_, mk) ->
            if (i == 0 || mapped[i - 1].second != mk) MonthMarker(mk, tiles[i]) else null
        }
        // Months tracked = distinct months with any LOGGED txn (investments count — showing
        // up is showing up). The house is the monument to sticking with it.
        val monthsTracked = ordered.map { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) }.distinct().size

        val currentTxns = ordered.filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == ym }
        val leafSums = currentTxns.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val severity = MonthStatsFolder.fold(categories, leafSums, currentBudgets, today.dayOfMonth, daysInMonth).overallSeverity
        val dayTotals = currentTxns.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }
            .mapValues { (_, l) -> l.sumOf { it.amountPaise } }
        val overall = currentBudgets.firstOrNull { it.categoryId == null }?.amountPaise

        return GardenState(
            monthKey = ym.toString(),
            weather = weatherOf(severity),
            plants = plants,
            spentPaise = leafSums.values.sum(),
            backRowTreeCount = treeCount(allTimeInvestmentCount),
            trunkTier = allTimeInvestmentCount,
            butterflies = minOf(5, currentMonthEvents.count { it.type == "gate.dodged" }),
            streakDays = StreakMath.underPaceStreak(dayTotals, overall, today.dayOfMonth, daysInMonth),
            noSpendDays = StreakMath.noSpendDays(dayTotals, today.dayOfMonth),
            archived = false,
            gridRows = SpiralTiler.gridSide(mapped.size),
            gridCols = SpiralTiler.gridSide(mapped.size),
            monthMarkers = markers,
            houseLevel = houseLevel(monthsTracked),
        )
    }

    /** Hut → cottage → brick house → villa. Thresholds in months tracked (spec §5). */
    private fun houseLevel(monthsTracked: Int) = when {
        monthsTracked <= 2 -> 1
        monthsTracked <= 5 -> 2
        monthsTracked <= 11 -> 3
        else -> 4
    }

    private fun weatherOf(severity: Severity) = when (severity) {
        Severity.OK -> Weather.SUNNY
        Severity.PACE_WARNING -> Weather.OVERCAST
        Severity.BREACH -> Weather.DROUGHT
    }

    /** Tunable grove growth: 0 SIPs → no trees; then 1, 2 (≥10), 3 (≥25). Trunk thickens with every SIP. */
    private fun treeCount(sips: Int) = when {
        sips == 0 -> 0
        sips < 10 -> 1
        sips < 25 -> 2
        else -> 3
    }
}
