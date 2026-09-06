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
        houseLevelOverride: Int? = null,                     // 1C.7: fold the SAME txns at a
                                                             // previous house level, for the
                                                             // expansion tween's "before" state
        // 4A: already projected by the caller. This function stays JSON-free by construction —
        // reading a payload means org.json, which throws "not mocked" in JVM tests, and that
        // would make the whole fold untestable off-device.
        rareSignals: List<RareSignal> = emptyList(),
    ): GardenState {
        val ym = YearMonth.from(today)
        val daysInMonth = ym.lengthOfMonth()

        val tree = CategoryTree(categories)
        val ordered = allTxns.sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        // Months tracked = distinct months with any LOGGED txn (investments count — showing
        // up is showing up). The house is the monument to sticking with it.
        val monthsTracked = ordered.map { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) }.distinct().size
        // 1C.7: the house's footprint drives the tiling, so the level must be resolved first.
        // 4A also needs it before the earn pass, since house level is itself a landmark trigger.
        val level = houseLevelOverride ?: houseLevel(monthsTracked)

        // 4A: earns are derived on every fold, never stored (spec §4.2). A first mapping pass
        // establishes which purchases could carry a rare — a weed or a zombie must not consume
        // a seed earned by restraint — then the assignment feeds the real mapping pass.
        val eligibility = rareCandidates(ordered, tree)
        val earns = RareEngine.earns(
            rareSignals,
            noSpendByMonth(ordered, today, zone),
            breadthByMonth(ordered, tree, zone),
            level,
            zone,
        )
        val assignment = RarePairing.assign(earns, eligibility)

        // Pair each planted txn with its month — markers fall out of the same chronological pass.
        val mapped = ordered.mapNotNull { t ->
            PlantMapper.map(t, tree, rare = assignment[t.uuid]?.species)?.let { m ->
                m to YearMonth.from(Instant.ofEpochMilli(t.occurredAt).atZone(zone)).toString()
            }
        }
        val foot = SpiralTiler.footprint(level)
        // Hoisted to a local because three things now depend on it agreeing with itself: the
        // plant tiling, the landmark plots, and gridRows/gridCols below — which each called
        // gridSide() inline. Same arguments give the same answer, so this is not a fix; it is
        // removing the chance for a future edit to make one of them disagree, which would put
        // a landmark plot on a tile the plants think is plantable.
        val side = SpiralTiler.gridSide(mapped.size, foot)
        val tiles = SpiralTiler.tiles(mapped.size, foot)

        // 4B: landmarks take reserved plots, so they are placed independently of the plant
        // tiling rather than competing with it. Zipping truncates — a landmark with no plot, or
        // a plot with no landmark, simply does not appear. The two agree by construction
        // anyway: RareEngine emits one earn per house level 3..level, and landmarkCount derives
        // from that same level's footprint. `the reserved plot count always matches the number
        // of landmarks earned` in RareFoldTest pins that.
        val landmarks = RareCatalog.landmarkAssignment(earns)
            .map { it.second }
            .zip(SpiralTiler.landmarkTiles(side, foot))
            .map { (species, tile) -> PlacedLandmark(species, tile) }
        val plants = mapped.mapIndexed { i, (m, _) ->
            Plant(m.txnUuid, m.archetype, m.sizeTier, m.isWeed, tiles[i], m.seed, m.variant, rare = m.rare)
        }
        val markers = mapped.mapIndexedNotNull { i, (_, mk) ->
            if (i == 0 || mapped[i - 1].second != mk) MonthMarker(mk, tiles[i]) else null
        }

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
            gridRows = side,
            gridCols = side,
            monthMarkers = markers,
            houseLevel = level,
            landmarks = landmarks,
        )
    }

    /** Which purchases could carry a rare, in chronological order.
     *
     *  Public because the greenhouse album re-derives the same assignment to show HOW each
     *  species was earned. Sharing this is the point: two copies of "what counts as eligible"
     *  would eventually disagree, and the album would then claim a species the island does not
     *  actually show. */
    fun rareCandidates(
        ordered: List<TransactionEntity>,
        tree: CategoryTree,
    ): List<RarePairing.Candidate> = ordered.mapNotNull { t ->
        PlantMapper.map(t, tree)?.let { m ->
            RarePairing.Candidate(
                t.uuid, t.occurredAt, m.archetype,
                eligible = !m.isWeed && m.archetype != Archetype.ZOMBIE,
            )
        }
    }

    /** The longest CONSECUTIVE no-spend run per month (4A).
     *
     *  Consecutive, not counted — and the difference is the whole trigger. A total count is
     *  nearly free: nobody spends every day, so with one purchase in a twenty-day stretch you
     *  already have nineteen no-spend days. That is arithmetic, not restraint. A seven-day
     *  unbroken run is a real thing you had to do.
     *
     *  Only ELAPSED days count, matching StreakMath: a month still in progress cannot claim
     *  its remaining days. */
    private fun noSpendByMonth(
        ordered: List<TransactionEntity>,
        today: LocalDate,
        zone: ZoneId,
    ): Map<String, Int> {
        val currentMonth = YearMonth.from(today)
        return ordered.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) }
            .mapValues { (month, txns) ->
                val spentDays = txns.map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfMonth }.toSet()
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

    /** Distinct ROOT categories spent in, per month (4A) — the variety trigger. Roots, not
     *  leaves: eleven roots exist, so eight of them is genuine breadth, whereas eight leaves
     *  could all sit under Food & Drinks. */
    private fun breadthByMonth(
        ordered: List<TransactionEntity>,
        tree: CategoryTree,
        zone: ZoneId,
    ): Map<String, Int> =
        ordered.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)).toString() }
            .mapValues { (_, txns) ->
                txns.mapNotNull { tree.ancestorChain(it.categoryId).lastOrNull() }.distinct().size
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
