# Phase 1B: Per-Category Budgets + Dashboard — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-category budgets with subtree rollup wired into the gate and weed rule, a dashboard screen (month stats, category breakdown, pace), plus four riders: quick-pick chips, loading states, regret tagging, manual-entry backdating.

**Architecture:** Pure-Kotlin `stats/` module (tree rollup, pace projection, month fold) + a `GateAggregator` compose the existing `GateEvaluator` across scopes — worst severity wins. First Room migration (v1→v2) adds the missing `budget→category` FK. `LedgerRepository` gains scope evaluation and atomic crossing-event emission (`budget.breached` / `budget.pace_warning`) so the 1C garden can replay weather. New `DashboardViewModel`/`DashboardScreen`; `MainViewModel` stays capture/home-focused.

**Tech Stack:** Existing pinned matrix only (Kotlin 2.0.20, Compose BOM 2024.09.03, Room 2.6.1 + KSP, Navigation 2.8.1). `androidx.room:room-testing` is ALREADY in the catalog and `build.gradle.kts` (came with the 1A runner fix) — **no dependency changes in this plan**.

**Spec:** `docs/superpowers/specs/2026-07-06-phase1b-budgets-dashboard-design.md` (approved 2026-07-06)

---

## Agent guardrails (carry-over from 1A, still binding)

- Every Gradle command needs the Studio JBR: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` first in each shell.
- JVM test filtering: `./gradlew testDebugUnitTest --tests "..."` (the umbrella `test` task rejects `--tests`).
- Instrumented filtering: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>` — needs the emulator running (`adb devices` shows `emulator-5554`).
- Do NOT bump versions, add dependencies, or fix deprecation warnings. Known-acceptable warnings: AGP/compileSdk 35 notice; `@OptIn(ExperimentalMaterial3Api::class)` and `@OptIn(ExperimentalLayoutApi::class)` are expected, not problems to fix.
- If a step's output doesn't match its Expected line: STOP and report. Don't improvise.
- Plain commit messages. Never push (no remote). Commit exactly what each task says.
- The 1A suite (22 JVM + 4 instrumented) must stay green after every task. `GateEvaluatorTest`, `MoneyTest`, `UpiUriParserTest` are never modified.

## File structure

```
app/src/main/java/com/expensegarden/app/
  gate/GateEvaluator.kt        MODIFY  expose paceAllowancePaise (behavior identical)
  gate/GateAggregator.kt       CREATE  scopes → worst severity + most specific offender
  stats/CategoryTree.kt        CREATE  ancestor chain + subtree rollup (pure)
  stats/PaceProjector.kt       CREATE  projection + per-day allowance (pure)
  stats/MonthStatsFolder.kt    CREATE  sums+budgets+day → dashboard rows (pure)
  stats/ChipOrder.kt           CREATE  usage counts → top-8 chip order (pure)
  data/Entities.kt             MODIFY  BudgetEntity gains FK
  data/Migrations.kt           CREATE  MIGRATION_1_2
  data/AppDatabase.kt          MODIFY  version 2, addMigrations
  data/Daos.kt                 MODIFY  BudgetDao/TransactionDao/CategoryDao/GameEventDao additions; TxnRow gains categoryId+regret
  data/LedgerRepository.kt     MODIFY  month utils, evaluateGate, crossing events, setRegret
  ui/MainViewModel.kt          MODIFY  gate verdict, header state, chips, regret; budget setter moves out
  ui/DashboardViewModel.kt     CREATE
  ui/DashboardScreen.kt        CREATE
  ui/EntryScreen.kt            MODIFY  chips + backdating
  ui/HomeScreen.kt             MODIFY  skeleton, hint, tap-through, regret dialog, budget dialog removed
  MainActivity.kt              MODIFY  dashboard route
app/src/test/java/com/expensegarden/app/
  gate/GateAggregatorTest.kt, stats/CategoryTreeTest.kt, stats/PaceProjectorTest.kt,
  stats/MonthStatsFolderTest.kt, stats/ChipOrderTest.kt        CREATE (JVM)
app/src/androidTest/java/com/expensegarden/app/data/
  MigrationTest.kt, BudgetDaoTest.kt, CrossingEventTest.kt, RegretTest.kt   CREATE
app/schemas/com.expensegarden.app.data.AppDatabase/2.json     GENERATED, committed
```

---

### Task 1: Expose pace allowance in GateEvaluator

The repository (Task 10) needs the pace threshold as a number, not just a verdict. Extract it. For integer paise, `x > floor(a)` ⟺ `x > a`, so truncating the Double allowance to Long changes no outcomes — the 5 existing tests prove it.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/gate/GateEvaluator.kt`
- Test: `app/src/test/java/com/expensegarden/app/gate/GateEvaluatorTest.kt` (append only)

- [x] **Step 1: Append the failing test to GateEvaluatorTest**

```kotlin
    @Test fun `pace allowance is day-proportional with grace`() {
        // 10000₹ budget, day 10/30: 10000 * 10/30 * 1.15 = ₹3,833.33 → 383333 paise (floor)
        assertEquals(383_333L, GateEvaluator.paceAllowancePaise(1_000_000L, 10, 30))
    }
```

- [x] **Step 2: Run — expect compile failure (function missing)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.gate.GateEvaluatorTest"`
Expected: FAILED — `unresolved reference: paceAllowancePaise`

- [x] **Step 3: Implement — replace GateEvaluator body**

```kotlin
object GateEvaluator {
    private const val PACE_GRACE = 1.15

    /** Day-proportional spend allowance incl. grace, floored to paise. */
    fun paceAllowancePaise(budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long =
        (budgetPaise.toDouble() * dayOfMonth / daysInMonth * PACE_GRACE).toLong()

    fun evaluate(
        spentThisMonthPaise: Long,
        monthBudgetPaise: Long?,
        candidatePaise: Long,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): Severity {
        if (monthBudgetPaise == null || monthBudgetPaise <= 0) return Severity.OK
        val afterPayment = spentThisMonthPaise + candidatePaise
        if (afterPayment > monthBudgetPaise) return Severity.BREACH
        return if (afterPayment > paceAllowancePaise(monthBudgetPaise, dayOfMonth, daysInMonth)) Severity.PACE_WARNING
        else Severity.OK
    }
}
```

- [x] **Step 4: Run — all 6 gate tests pass**

Run: same command as Step 2.
Expected: BUILD SUCCESSFUL, 6 tests passed (the 5 originals prove behavior is unchanged).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/gate/GateEvaluator.kt app/src/test/java/com/expensegarden/app/gate/GateEvaluatorTest.kt
git commit -m "feat: expose pace allowance from gate evaluator"
```

---

### Task 2: stats/CategoryTree — ancestor chain + subtree rollup

Pure Kotlin over `parentId`; written for arbitrary depth even though the seed is 2 levels. This is the shared substrate for the gate scopes, the crossing events, and the dashboard — the reason they can never disagree.

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/stats/CategoryTree.kt`
- Create: `app/src/test/java/com/expensegarden/app/stats/CategoryTreeTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTreeTest {
    private fun cat(id: Long, parent: Long? = null) =
        CategoryEntity(id = id, name = "c$id", parentId = parent, isNecessity = false)

    // Seed-shaped: 1=Food (parent), 103=Chai (child of 1), 3=Transport, plus a synthetic depth-3 chain 6→601→9001
    private val tree = CategoryTree(listOf(
        cat(1), cat(3), cat(6), cat(103, parent = 1), cat(601, parent = 6), cat(9001, parent = 601),
    ))

    @Test fun `ancestor chain runs self first, root last`() =
        assertEquals(listOf(103L, 1L), tree.ancestorChain(103))

    @Test fun `ancestor chain of a root is just itself`() =
        assertEquals(listOf(3L), tree.ancestorChain(3))

    @Test fun `ancestor chain handles depth three`() =
        assertEquals(listOf(9001L, 601L, 6L), tree.ancestorChain(9001))

    @Test fun `rollup adds descendants into every ancestor`() {
        val rolled = tree.rollupSums(mapOf(103L to 2_000L, 1L to 500L, 9001L to 100L))
        assertEquals(2_500L, rolled[1L])      // own 500 + child 103's 2000
        assertEquals(2_000L, rolled[103L])
        assertEquals(100L, rolled[6L])        // grandchild rolls all the way up
        assertEquals(100L, rolled[601L])
        assertEquals(0L, rolled[3L])          // untouched category present with 0
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.stats.CategoryTreeTest"`
Expected: FAILED — `unresolved reference: CategoryTree`

- [x] **Step 3: Implement**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity

/** Pure view over the category taxonomy. Depth-agnostic, though the seed is two levels. */
class CategoryTree(categories: List<CategoryEntity>) {
    private val byId: Map<Long, CategoryEntity> = categories.associateBy { it.id }

    fun byId(id: Long): CategoryEntity? = byId[id]

    /** [self, parent, …, root]. Unknown ids yield an empty list. */
    fun ancestorChain(categoryId: Long): List<Long> {
        val chain = mutableListOf<Long>()
        var cursor = byId[categoryId]
        while (cursor != null) {
            chain += cursor.id
            cursor = cursor.parentId?.let { byId[it] }
        }
        return chain
    }

    /** Every known category id → own spend + all descendants' spend. Missing input = 0. */
    fun rollupSums(leafSums: Map<Long, Long>): Map<Long, Long> {
        val rolled = byId.keys.associateWith { 0L }.toMutableMap()
        for ((leafId, amount) in leafSums) {
            for (ancestorId in ancestorChain(leafId)) {
                rolled[ancestorId] = (rolled[ancestorId] ?: 0L) + amount
            }
        }
        return rolled
    }
}
```

- [x] **Step 4: Run to verify pass**

Run: same command. Expected: BUILD SUCCESSFUL, 4 tests passed.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/stats/CategoryTree.kt app/src/test/java/com/expensegarden/app/stats/CategoryTreeTest.kt
git commit -m "feat: category tree with ancestor chain and subtree rollup"
```

---

### Task 3: stats/PaceProjector

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/stats/PaceProjector.kt`
- Create: `app/src/test/java/com/expensegarden/app/stats/PaceProjectorTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceProjectorTest {
    @Test fun `linear projection scales spend to month length`() =
        assertEquals(900_000L, PaceProjector.projectedMonthEndPaise(300_000L, 10, 30))

    @Test fun `projection on day one is spend times month length`() =
        assertEquals(3_000_000L, PaceProjector.projectedMonthEndPaise(100_000L, 1, 30))

    @Test fun `per-day allowance divides remaining budget over remaining days incl today`() {
        // (10000₹ - 4000₹) / 21 days (day 10 of 30, today counts) = ₹285.71 → 28571 paise
        assertEquals(28_571L, PaceProjector.perDayToStayUnderPaise(400_000L, 1_000_000L, 10, 30))
    }

    @Test fun `per-day allowance floors at zero once budget is gone`() =
        assertEquals(0L, PaceProjector.perDayToStayUnderPaise(1_200_000L, 1_000_000L, 10, 30))

    @Test fun `per-day allowance on the last day is the whole remainder`() =
        assertEquals(50_000L, PaceProjector.perDayToStayUnderPaise(950_000L, 1_000_000L, 30, 30))
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.stats.PaceProjectorTest"`
Expected: FAILED — `unresolved reference: PaceProjector`

- [x] **Step 3: Implement**

```kotlin
package com.expensegarden.app.stats

/** Classical-stats month pace (spec §8.3). Money stays paise-Long; division truncates at display precision. */
object PaceProjector {
    fun projectedMonthEndPaise(spentPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long {
        val day = dayOfMonth.coerceAtLeast(1)
        return spentPaise * daysInMonth / day
    }

    /** Remaining budget spread over the remaining days, today included. 0 when over. */
    fun perDayToStayUnderPaise(spentPaise: Long, budgetPaise: Long, dayOfMonth: Int, daysInMonth: Int): Long {
        val daysLeft = (daysInMonth - dayOfMonth + 1).coerceAtLeast(1)
        return ((budgetPaise - spentPaise) / daysLeft).coerceAtLeast(0L)
    }
}
```

- [x] **Step 4: Run to verify pass** — same command. Expected: 5 tests passed.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/stats/PaceProjector.kt app/src/test/java/com/expensegarden/app/stats/PaceProjectorTest.kt
git commit -m "feat: pace projector - linear month-end projection and per-day allowance"
```

---

### Task 4: gate/GateAggregator — worst severity wins, most specific offender named

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/gate/GateAggregator.kt`
- Create: `app/src/test/java/com/expensegarden/app/gate/GateAggregatorTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GateAggregatorTest {
    // depth: overall=0, parent=1, child=2 (deeper = more specific)
    private val overall = ScopeInput(categoryId = null, label = "overall", budgetPaise = 1_000_000L, spentPaise = 0L, depth = 0)

    @Test fun `no scopes means OK and no offender`() {
        val v = GateAggregator.aggregate(emptyList(), 10_000L, 15, 30)
        assertEquals(Severity.OK, v.severity)
        assertNull(v.offender)
    }

    @Test fun `category breach beats overall ok`() {
        val food = ScopeInput(1L, "Food & Drinks", budgetPaise = 50_000L, spentPaise = 45_000L, depth = 1)
        val v = GateAggregator.aggregate(listOf(overall, food), 10_000L, 15, 30)
        assertEquals(Severity.BREACH, v.severity)
        assertEquals("Food & Drinks", v.offender?.label)
    }

    @Test fun `overall breach alone still fires`() {
        val v = GateAggregator.aggregate(listOf(overall.copy(spentPaise = 995_000L)), 10_000L, 15, 30)
        assertEquals(Severity.BREACH, v.severity)
        assertNull(v.offender?.categoryId)
    }

    @Test fun `at equal severity the deepest scope is named`() {
        // both parent and child breach; child (depth 2) must be the offender
        val parent = ScopeInput(1L, "Food & Drinks", budgetPaise = 10_000L, spentPaise = 9_000L, depth = 1)
        val child = ScopeInput(103L, "Chai & Snacks", budgetPaise = 5_000L, spentPaise = 4_500L, depth = 2)
        val v = GateAggregator.aggregate(listOf(overall, parent, child), 2_000L, 15, 30)
        assertEquals(Severity.BREACH, v.severity)
        assertEquals("Chai & Snacks", v.offender?.label)
    }

    @Test fun `pace warning surfaces when nothing breaches`() {
        // day 10/30 allowance on 10000₹ = ₹3,833.33; spent 3000₹ + 1000₹ = 4000₹ > allowance
        val v = GateAggregator.aggregate(listOf(overall.copy(spentPaise = 300_000L)), 100_000L, 10, 30)
        assertEquals(Severity.PACE_WARNING, v.severity)
        assertEquals("overall", v.offender?.label)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.gate.GateAggregatorTest"`
Expected: FAILED — `unresolved reference: ScopeInput`

- [x] **Step 3: Implement**

```kotlin
package com.expensegarden.app.gate

/** One budget scope at gate time. depth: 0 = overall, then 1 per taxonomy level (deeper = more specific). */
data class ScopeInput(
    val categoryId: Long?,
    val label: String,
    val budgetPaise: Long,
    val spentPaise: Long,
    val depth: Int,
)

/** Worst severity across scopes; offender = deepest scope at that severity (null when OK). */
data class GateVerdict(val severity: Severity, val offender: ScopeInput?)

object GateAggregator {
    fun aggregate(scopes: List<ScopeInput>, candidatePaise: Long, dayOfMonth: Int, daysInMonth: Int): GateVerdict {
        val evaluated = scopes.map { scope ->
            scope to GateEvaluator.evaluate(scope.spentPaise, scope.budgetPaise, candidatePaise, dayOfMonth, daysInMonth)
        }
        val worst = evaluated.maxOfOrNull { it.second } ?: Severity.OK   // enum order: OK < PACE_WARNING < BREACH
        if (worst == Severity.OK) return GateVerdict(Severity.OK, null)
        val offender = evaluated.filter { it.second == worst }.maxBy { it.first.depth }.first
        return GateVerdict(worst, offender)
    }
}
```

- [x] **Step 4: Run to verify pass** — same command. Expected: 5 tests passed.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/gate/GateAggregator.kt app/src/test/java/com/expensegarden/app/gate/GateAggregatorTest.kt
git commit -m "feat: gate aggregator - worst severity across budget scopes, deepest offender named"
```

---

### Task 5: stats/MonthStatsFolder — the dashboard's single source of numbers

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/stats/MonthStatsFolder.kt`
- Create: `app/src/test/java/com/expensegarden/app/stats/MonthStatsFolderTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.gate.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthStatsFolderTest {
    private val categories = listOf(
        CategoryEntity(1, "Food & Drinks", null, false),
        CategoryEntity(3, "Transport", null, true),
        CategoryEntity(103, "Chai & Snacks", 1, false),
    )
    private fun budget(catId: Long?, paise: Long) = BudgetEntity(categoryId = catId, month = "2026-07", amountPaise = paise)

    @Test fun `header carries total, overall budget, projection`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 300_000L), listOf(budget(null, 1_000_000L)), 10, 30)
        assertEquals(300_000L, s.spentPaise)
        assertEquals(1_000_000L, s.overallBudgetPaise)
        assertEquals(900_000L, s.projectedPaise)                    // 3000₹ * 30/10
        assertEquals(33_333L, s.perDayPaise)                        // (10000₹-3000₹)/21 days left incl today
    }

    @Test fun `per-day uses remaining days including today`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 400_000L), listOf(budget(null, 1_000_000L)), 10, 30)
        assertEquals(28_571L, s.perDayPaise)                        // (10000-4000)/21
    }

    @Test fun `no overall budget means no per-day figure`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 400_000L), emptyList(), 10, 30)
        assertNull(s.overallBudgetPaise)
        assertNull(s.perDayPaise)
    }

    @Test fun `rows list parents always, children only when active`() {
        val s = MonthStatsFolder.fold(categories, mapOf(103L to 100L), emptyList(), 10, 30)
        assertEquals(listOf("Food & Drinks", "Chai & Snacks", "Transport"), s.rows.map { it.name })
        assertEquals(listOf(false, true, false), s.rows.map { it.indent })
    }

    @Test fun `inactive children are hidden`() {
        val s = MonthStatsFolder.fold(categories, emptyMap(), emptyList(), 10, 30)
        assertEquals(listOf("Food & Drinks", "Transport"), s.rows.map { it.name })
    }

    @Test fun `budgeted child appears even with zero spend and rows carry rolled sums and severity`() {
        val s = MonthStatsFolder.fold(
            categories, mapOf(103L to 60_000L), listOf(budget(1L, 50_000L), budget(103L, 100_000L)), 15, 30,
        )
        val food = s.rows.first { it.categoryId == 1L }
        assertEquals(60_000L, food.spentPaise)                      // rolled up from the child
        assertEquals(Severity.BREACH, food.severity)                // 600 > 500₹ budget
        val chai = s.rows.first { it.categoryId == 103L }
        assertEquals(Severity.OK, chai.severity)                    // 600 vs own 1000₹: fine at day 15
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.stats.MonthStatsFolderTest"`
Expected: FAILED — `unresolved reference: MonthStatsFolder`

- [x] **Step 3: Implement**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.gate.GateEvaluator
import com.expensegarden.app.gate.Severity

data class ScopeStat(
    val categoryId: Long?,
    val name: String,
    val indent: Boolean,
    val spentPaise: Long,
    val budgetPaise: Long?,
    val severity: Severity,          // state severity (candidate 0); OK when unbudgeted
)

data class MonthStats(
    val spentPaise: Long,
    val overallBudgetPaise: Long?,
    val overallSeverity: Severity,
    val projectedPaise: Long,
    val perDayPaise: Long?,
    val rows: List<ScopeStat>,
)

object MonthStatsFolder {
    fun fold(
        categories: List<CategoryEntity>,
        leafSums: Map<Long, Long>,
        budgets: List<BudgetEntity>,
        dayOfMonth: Int,
        daysInMonth: Int,
    ): MonthStats {
        val tree = CategoryTree(categories)
        val rolled = tree.rollupSums(leafSums)
        val budgetByScope = budgets.associateBy { it.categoryId }
        val total = leafSums.values.sum()
        val overall = budgetByScope[null]?.amountPaise

        fun stateSeverity(spent: Long, budget: Long?): Severity =
            GateEvaluator.evaluate(spent, budget, 0L, dayOfMonth, daysInMonth)

        fun rowFor(cat: CategoryEntity, indent: Boolean): ScopeStat {
            val spent = rolled[cat.id] ?: 0L
            val budget = budgetByScope[cat.id]?.amountPaise
            return ScopeStat(cat.id, cat.name, indent, spent, budget, stateSeverity(spent, budget))
        }

        val parents = categories.filter { it.parentId == null }.sortedBy { it.id }
        val rows = buildList {
            for (parent in parents) {
                add(rowFor(parent, indent = false))
                categories.filter { it.parentId == parent.id }.sortedBy { it.id }.forEach { child ->
                    val active = (rolled[child.id] ?: 0L) > 0L || budgetByScope.containsKey(child.id)
                    if (active) add(rowFor(child, indent = true))
                }
            }
        }

        return MonthStats(
            spentPaise = total,
            overallBudgetPaise = overall,
            overallSeverity = stateSeverity(total, overall),
            projectedPaise = PaceProjector.projectedMonthEndPaise(total, dayOfMonth, daysInMonth),
            perDayPaise = overall?.let { PaceProjector.perDayToStayUnderPaise(total, it, dayOfMonth, daysInMonth) },
            rows = rows,
        )
    }
}
```

- [x] **Step 4: Run to verify pass** — same command. Expected: 6 tests passed.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/stats/MonthStatsFolder.kt app/src/test/java/com/expensegarden/app/stats/MonthStatsFolderTest.kt
git commit -m "feat: month stats folder - rolled category rows, projection, per-day allowance"
```

---

### Task 6: stats/ChipOrder

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/stats/ChipOrder.kt`
- Create: `app/src/test/java/com/expensegarden/app/stats/ChipOrderTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ChipOrderTest {
    private val cats = (1L..11L).map { CategoryEntity(it, "c$it", null, false) } +
        listOf(CategoryEntity(103, "chai", 1, false))

    @Test fun `used categories lead, by count desc, tie broken by lower id`() {
        val chips = ChipOrder.topChips(cats, mapOf(103L to 5, 3L to 2, 7L to 2), limit = 4)
        assertEquals(listOf(103L, 3L, 7L, 1L), chips.map { it.id })   // 3 before 7 (tie, lower id); fill with id-asc
    }

    @Test fun `thin history fills with seed order`() {
        val chips = ChipOrder.topChips(cats, emptyMap(), limit = 8)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), chips.map { it.id })
    }

    @Test fun `limit caps the list`() =
        assertEquals(8, ChipOrder.topChips(cats, mapOf(103L to 1), limit = 8).size)
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest --tests "com.expensegarden.app.stats.ChipOrderTest"`
Expected: FAILED — `unresolved reference: ChipOrder`

- [x] **Step 3: Implement**

```kotlin
package com.expensegarden.app.stats

import com.expensegarden.app.data.CategoryEntity

/** Quick-pick order: usage count desc (LOGGED txns, last 90 days), then seed order (id asc) as filler. */
object ChipOrder {
    fun topChips(categories: List<CategoryEntity>, usageCounts: Map<Long, Int>, limit: Int = 8): List<CategoryEntity> =
        categories
            .sortedWith(compareByDescending<CategoryEntity> { usageCounts[it.id] ?: 0 }.thenBy { it.id })
            .take(limit)
}
```

- [x] **Step 4: Run to verify pass** — same command. Expected: 3 tests passed.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/stats/ChipOrder.kt app/src/test/java/com/expensegarden/app/stats/ChipOrderTest.kt
git commit -m "feat: chip ordering - usage-ranked quick picks with seed-order fill"
```

---

### Task 7: Room migration v1→v2 — budget gains its FK

SQLite can't `ALTER TABLE ADD CONSTRAINT`; the migration recreates the table. `MigrationTestHelper` validates the migrated schema byte-for-byte against the generated `2.json`, so the migration SQL must reproduce what Room expects.

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/Entities.kt` (BudgetEntity annotation)
- Modify: `app/src/main/java/com/expensegarden/app/data/AppDatabase.kt` (version 2, addMigrations)
- Create: `app/src/main/java/com/expensegarden/app/data/Migrations.kt`
- Create: `app/src/androidTest/java/com/expensegarden/app/data/MigrationTest.kt`
- Generated: `app/schemas/com.expensegarden.app.data.AppDatabase/2.json` (commit it)

- [x] **Step 1: Write the failing migration test**

```kotlin
package com.expensegarden.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2_preservesRows_andEnforcesFk() {
        // v1 database with hand-inserted rows (helper creates schema only — no SeedCallback).
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO category (id, name, parentId, isNecessity) VALUES (1, 'Food', NULL, 0)")
            execSQL("INSERT INTO budget (categoryId, month, amountPaise) VALUES (NULL, '2026-07', 1000000)")
            execSQL("INSERT INTO budget (categoryId, month, amountPaise) VALUES (1, '2026-07', 50000)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2)

        // Rows survived — overall (NULL FK is vacuously valid) and the category-scoped one.
        db.query("SELECT categoryId, month, amountPaise FROM budget ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertTrue(c.isNull(0))
            assertEquals("2026-07", c.getString(1))
            assertEquals(1_000_000L, c.getLong(2))
            c.moveToNext()
            assertEquals(1L, c.getLong(0))
            assertEquals(50_000L, c.getLong(2))
        }

        // FK now enforced: unknown category must be rejected.
        db.execSQL("PRAGMA foreign_keys = ON")
        var rejected = false
        try {
            db.execSQL("INSERT INTO budget (categoryId, month, amountPaise) VALUES (999, '2026-08', 1)")
        } catch (e: SQLiteConstraintException) {
            rejected = true
        }
        assertTrue("insert with bogus categoryId must violate the new FK", rejected)
    }
}
```

- [x] **Step 2: Run — expect compile failure (MIGRATION_1_2 missing)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.MigrationTest`
Expected: compile FAILED — `unresolved reference: MIGRATION_1_2`. (Emulator must be up: `adb devices` → `emulator-5554 device`.)

- [x] **Step 3: Update BudgetEntity — add the FK**

In `Entities.kt`, replace the `budget` entity annotation block:

```kotlin
@Entity(
    tableName = "budget",
    indices = [Index(value = ["categoryId", "month"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.CASCADE,   // a budget without its category is meaningless; categories are seed-only, so belt-and-braces
    )],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,                 // null = overall budget
    val month: String,                     // "2026-07"
    val amountPaise: Long,
)
```

- [x] **Step 4: Create Migrations.kt**

```kotlin
package com.expensegarden.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1→v2: budget gains FOREIGN KEY (categoryId) → category(id) ON DELETE CASCADE.
 * SQLite can't add a constraint in place: recreate, copy, swap, re-index.
 * The CREATE TABLE below must match 2.json's createSql exactly (Room validates in tests).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`categoryId` INTEGER, `month` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, " +
                "FOREIGN KEY(`categoryId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("INSERT INTO budget_new (id, categoryId, month, amountPaise) SELECT id, categoryId, month, amountPaise FROM budget")
        db.execSQL("DROP TABLE budget")
        db.execSQL("ALTER TABLE budget_new RENAME TO budget")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_categoryId_month` ON `budget` (`categoryId`, `month`)")
    }
}
```

- [x] **Step 5: Bump version and register the migration in AppDatabase.kt**

Change the `@Database` annotation line and the builder:

```kotlin
    version = 2,
```

```kotlin
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "garden.db")
                .addMigrations(MIGRATION_1_2)
                .addCallback(SeedCallback)
                .build()
```

- [x] **Step 6: Build to export 2.json, then reconcile the migration SQL**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin && python3 -c "
import json
d = json.load(open('app/schemas/com.expensegarden.app.data.AppDatabase/2.json'))
e = [x for x in d['database']['entities'] if x['tableName'] == 'budget'][0]
print(e['createSql']); print(e['indices'][0]['createSql']); print(e['foreignKeys'])"`
Expected: `2.json` exists; `createSql` reads `CREATE TABLE IF NOT EXISTS \`${TABLE_NAME}\` (\`id\` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, \`categoryId\` INTEGER, \`month\` TEXT NOT NULL, \`amountPaise\` INTEGER NOT NULL, FOREIGN KEY(\`categoryId\`) REFERENCES \`category\`(\`id\`) ON UPDATE NO ACTION ON DELETE CASCADE )`; the FK entry shows `"onDelete": "CASCADE"`.
If the printed createSql differs in ANY way from the migration's CREATE TABLE (modulo `${TABLE_NAME}` → `budget_new`): edit Migrations.kt to match it exactly, don't negotiate.

- [x] **Step 7: Run the migration test**

Run: the Step 2 command.
Expected: BUILD SUCCESSFUL, 1 test passed (validation includes the schema diff — a mismatch fails loudly with both schemas printed).

- [x] **Step 8: Run the full existing instrumented suite (regression)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL — 4 old + 1 new instrumented tests pass (fresh in-memory DBs create at v2 directly; SeedCallback unaffected).

- [x] **Step 9: Commit (schema history included)**

```bash
git add app/src/main/java/com/expensegarden/app/data/Entities.kt app/src/main/java/com/expensegarden/app/data/Migrations.kt app/src/main/java/com/expensegarden/app/data/AppDatabase.kt app/src/androidTest/java/com/expensegarden/app/data/MigrationTest.kt app/schemas/com.expensegarden.app.data.AppDatabase/2.json
git commit -m "feat: room migration v1-v2 - budget gains category fk with cascade"
```

---

### Task 8: DAO additions + TxnRow gains category/regret

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/Daos.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt` (compile-only: TxnRow call sites unchanged — new fields are additive; verify build)
- Create: `app/src/androidTest/java/com/expensegarden/app/data/BudgetDaoTest.kt`

- [x] **Step 1: Write the failing DAO tests**

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BudgetDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After fun teardown() = db.close()

    private suspend fun logTxn(categoryId: Long, paise: Long, at: Long) {
        val payeeId = db.payeeDao().insert(PayeeEntity(name = "p$at", vpa = null, defaultCategoryId = null))
        db.transactionDao().insert(
            TransactionEntity(
                uuid = UUID.randomUUID().toString(), amountPaise = paise, payeeId = payeeId,
                categoryId = categoryId, source = TxnSource.MANUAL, status = TxnStatus.LOGGED,
                breachedAtLogging = false, note = null, occurredAt = at, createdAt = at,
            )
        )
    }

    @Test fun budget_scope_crud_null_and_category_are_distinct_rows() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-07", amountPaise = 1_000_000))
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = "2026-07", amountPaise = 50_000))
        assertEquals(2, db.budgetDao().allForMonth("2026-07").size)

        // SQL NULL never matches `categoryId = ?` — the category delete must not touch the overall row.
        db.budgetDao().deleteForCategory(1, "2026-07")
        val left = db.budgetDao().allForMonth("2026-07")
        assertEquals(1, left.size)
        assertEquals(null, left.single().categoryId)

        db.budgetDao().deleteOverallForMonth("2026-07")
        assertEquals(0, db.budgetDao().allForMonth("2026-07").size)
    }

    @Test fun sums_by_category_group_logged_only() = runBlocking {
        logTxn(categoryId = 103, paise = 2_000, at = 1_000L)
        logTxn(categoryId = 103, paise = 3_000, at = 1_100L)
        logTxn(categoryId = 3, paise = 500, at = 1_200L)
        val sums = db.transactionDao().loggedSumsByCategory(0L, 2_000L).associate { it.categoryId to it.totalPaise }
        assertEquals(5_000L, sums[103L])
        assertEquals(500L, sums[3L])
    }

    @Test fun usage_counts_count_rows_not_amounts() = runBlocking {
        logTxn(categoryId = 103, paise = 1, at = 1_000L)
        logTxn(categoryId = 103, paise = 1, at = 1_100L)
        logTxn(categoryId = 3, paise = 999_999, at = 1_200L)
        val usage = db.transactionDao().categoryUsageSince(0L).associate { it.categoryId to it.uses }
        assertEquals(2, usage[103L])
        assertEquals(1, usage[3L])
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.BudgetDaoTest`
Expected: compile FAILED — `unresolved reference: allForMonth` (and friends).

- [x] **Step 3: Implement the DAO additions in Daos.kt**

Extend `TxnRow` (same file, replaces the old data class):

```kotlin
data class TxnRow(
    val uuid: String,
    val amountPaise: Long,
    val payeeName: String,
    val categoryName: String,
    val categoryId: Long,
    val regret: Regret,
    val occurredAt: Long,
)
```

Replace `observeRecent`'s query so the new columns are selected:

```kotlin
    @Query(
        """SELECT t.uuid, t.amountPaise, p.name AS payeeName, c.name AS categoryName,
                  t.categoryId, t.regret, t.occurredAt
           FROM txn t JOIN payee p ON p.id = t.payeeId JOIN category c ON c.id = t.categoryId
           WHERE t.status = 'LOGGED' ORDER BY t.occurredAt DESC LIMIT 50"""
    )
    fun observeRecent(): Flow<List<TxnRow>>
```

Append to `TransactionDao`:

```kotlin
    @Query("SELECT * FROM txn WHERE uuid = :uuid")
    suspend fun byUuid(uuid: String): TransactionEntity?

    @Query("UPDATE txn SET regret = :regret WHERE uuid = :uuid")
    suspend fun setRegret(uuid: String, regret: Regret)

    @Query(
        """SELECT categoryId, COALESCE(SUM(amountPaise), 0) AS totalPaise FROM txn
           WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis GROUP BY categoryId"""
    )
    suspend fun loggedSumsByCategory(fromMillis: Long, toMillis: Long): List<CategorySum>

    @Query(
        """SELECT categoryId, COALESCE(SUM(amountPaise), 0) AS totalPaise FROM txn
           WHERE status = 'LOGGED' AND occurredAt BETWEEN :fromMillis AND :toMillis GROUP BY categoryId"""
    )
    fun observeLoggedSumsByCategory(fromMillis: Long, toMillis: Long): Flow<List<CategorySum>>

    @Query(
        """SELECT categoryId, COUNT(*) AS uses FROM txn
           WHERE status = 'LOGGED' AND occurredAt >= :sinceMillis GROUP BY categoryId"""
    )
    fun observeCategoryUsageSince(sinceMillis: Long): Flow<List<CategoryUsage>>

    @Query(
        """SELECT categoryId, COUNT(*) AS uses FROM txn
           WHERE status = 'LOGGED' AND occurredAt >= :sinceMillis GROUP BY categoryId"""
    )
    suspend fun categoryUsageSince(sinceMillis: Long): List<CategoryUsage>
```

Add the projection rows near `TxnRow`:

```kotlin
data class CategorySum(val categoryId: Long, val totalPaise: Long)
data class CategoryUsage(val categoryId: Long, val uses: Int)
```

Append to `BudgetDao`:

```kotlin
    @Query("SELECT * FROM budget WHERE month = :month")
    suspend fun allForMonth(month: String): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE month = :month")
    fun observeAllForMonth(month: String): Flow<List<BudgetEntity>>

    @Query("DELETE FROM budget WHERE categoryId = :categoryId AND month = :month")
    suspend fun deleteForCategory(categoryId: Long, month: String)
```

Append to `CategoryDao`:

```kotlin
    @Query("SELECT * FROM category")
    suspend fun all(): List<CategoryEntity>
```

Append to `GameEventDao` (tests + the 1C fold both need to read):

```kotlin
    @Query("SELECT * FROM game_event ORDER BY id")
    suspend fun allByIdAsc(): List<GameEventEntity>
```

- [x] **Step 4: Run to verify pass**

Run: the Step 2 command. Expected: 3 tests passed.
Then: `./gradlew connectedDebugAndroidTest` — all instrumented green (TxnRow change compiles HomeScreen untouched: field additions only).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/Daos.kt app/src/androidTest/java/com/expensegarden/app/data/BudgetDaoTest.kt
git commit -m "feat: dao additions - scoped budgets, category sums, usage counts, regret update"
```

---

### Task 9: Repository — month utilities + scope-aware gate evaluation

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt` (only what's needed to keep the build green)
- Create: `app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt`

- [x] **Step 1: Write the failing repository test**

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.expensegarden.app.gate.Severity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class GateScopeTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository
    private val zone = ZoneId.systemDefault()
    private val nowMillis = System.currentTimeMillis()
    private val month = java.time.YearMonth.now(zone).toString()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }

    @After fun teardown() = db.close()

    @Test fun category_budget_breach_wins_over_healthy_overall() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = 10_000_000))  // ₹1,00,000 overall
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 50_000))         // ₹500 on Food & Drinks
        // ₹400 already logged under Chai & Snacks (child of Food & Drinks) this month
        repo.saveManualLogged(
            LedgerRepository.Draft(vpa = null, payeeName = "Chaiwala", amountPaise = 40_000,
                categoryId = 103, note = null, occurredAt = nowMillis),
            breachedAtLogging = false,
        )
        // Candidate ₹200 under Chai: child rolls into the Food budget → 400+200 > 500 → BREACH, offender = Food & Drinks
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 20_000, occurredAt = nowMillis)
        assertEquals(Severity.BREACH, verdict.severity)
        assertEquals(1L, verdict.offender?.categoryId)
        assertEquals("Food & Drinks", verdict.offender?.label)
    }

    @Test fun no_budgets_at_all_is_ok() = runBlocking {
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 1_000_000, occurredAt = nowMillis)
        assertEquals(Severity.OK, verdict.severity)
    }

    @Test fun backdated_evaluation_uses_that_months_budget_and_spend() = runBlocking {
        val lastMonth = java.time.YearMonth.now(zone).minusMonths(1)
        val lastMonthMillis = lastMonth.atDay(15).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = lastMonth.toString(), amountPaise = 1_000))
        // ₹50 candidate against last month's tiny ₹10 budget → BREACH even though this month has no budget
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 5_000, occurredAt = lastMonthMillis)
        assertEquals(Severity.BREACH, verdict.severity)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest`
Expected: compile FAILED — `unresolved reference: evaluateGate`.

- [x] **Step 3: Implement in LedgerRepository.kt**

New imports at top:

```kotlin
import com.expensegarden.app.gate.GateAggregator
import com.expensegarden.app.gate.GateVerdict
import com.expensegarden.app.gate.ScopeInput
import com.expensegarden.app.stats.CategoryTree
import java.time.Instant
```

Replace `observeMonthSpent`/`currentMonthBounds` and add month utilities (the old zero-arg `observeMonthSpent()` is deleted — its bounds froze at flow creation and went stale across month boundaries):

```kotlin
    fun observeMonthSpent(monthKey: String): Flow<Long> {
        val (from, to) = boundsOfMonth(monthKey)
        return db.transactionDao().observeLoggedSumBetween(from, to)
    }

    suspend fun monthSpentPaise(monthKey: String = currentMonthKey()): Long {
        val (from, to) = boundsOfMonth(monthKey)
        return db.transactionDao().loggedSumBetween(from, to)
    }

    fun monthKeyOf(epochMillis: Long): String =
        YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone)).toString()

    fun boundsOfMonth(monthKey: String): Pair<Long, Long> {
        val ym = YearMonth.parse(monthKey)
        val from = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return from to to
    }

    fun dayAndLengthOf(epochMillis: Long): Pair<Int, Int> {
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return d.dayOfMonth to d.lengthOfMonth()
    }
```

(Delete the old private `currentMonthBounds()`; `currentMonthKey()` and `today()` stay.)

Add scope assembly + gate evaluation:

```kotlin
    /** Budget scopes relevant to a payment in [categoryId] during [occurredAt]'s month:
     *  overall (depth 0) + every budgeted category on the ancestor chain (deeper = more specific). */
    suspend fun scopeInputs(categoryId: Long, occurredAt: Long): List<ScopeInput> {
        val monthKey = monthKeyOf(occurredAt)
        val budgets = db.budgetDao().allForMonth(monthKey)
        if (budgets.isEmpty()) return emptyList()
        val tree = CategoryTree(db.categoryDao().all())
        val chain = tree.ancestorChain(categoryId)                       // [self, …, root]
        val (from, to) = boundsOfMonth(monthKey)
        val leafSums = db.transactionDao().loggedSumsByCategory(from, to)
            .associate { it.categoryId to it.totalPaise }
        val rolled = tree.rollupSums(leafSums)
        return budgets.mapNotNull { b ->
            when {
                b.categoryId == null -> ScopeInput(null, "overall", b.amountPaise, leafSums.values.sum(), depth = 0)
                b.categoryId in chain -> ScopeInput(
                    categoryId = b.categoryId,
                    label = tree.byId(b.categoryId)?.name ?: "?",
                    budgetPaise = b.amountPaise,
                    spentPaise = rolled[b.categoryId] ?: 0L,
                    depth = chain.size - chain.indexOf(b.categoryId),    // self deepest
                )
                else -> null
            }
        }
    }

    /** Worst severity across scopes, evaluated in the month the txn belongs to (spec §3: backdating). */
    suspend fun evaluateGate(categoryId: Long, amountPaise: Long, occurredAt: Long): GateVerdict {
        val (day, days) = dayAndLengthOf(occurredAt)
        return GateAggregator.aggregate(scopeInputs(categoryId, occurredAt), amountPaise, day, days)
    }
```

- [x] **Step 4: Keep MainViewModel compiling (minimal edits, full rework comes in Task 12)**

In `MainViewModel.kt`, replace the two call sites of the changed APIs:

```kotlin
    val monthSpent: StateFlow<Long> =
        ledger.observeMonthSpent(ledger.currentMonthKey())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
```

In `prepareGate` and `saveManualFromDraft`, replace `ledger.monthSpentPaise()` with `ledger.monthSpentPaise()` — no change needed (default parameter covers it). Verify with the build.

- [x] **Step 5: Run to verify pass**

Run: the Step 2 command. Expected: 3 tests passed.
Then: `./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest` — everything green.

- [x] **Step 6: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "feat: scope-aware gate evaluation - rollup budgets, backdated-month semantics"
```

---

### Task 10: Crossing events — the garden's weather feed

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`
- Create: `app/src/androidTest/java/com/expensegarden/app/data/CrossingEventTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CrossingEventTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository
    private val zone = ZoneId.systemDefault()
    private val month = YearMonth.now(zone).toString()
    private val nowMillis = System.currentTimeMillis()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }

    @After fun teardown() = db.close()

    private fun draft(paise: Long, categoryId: Long = 103, at: Long = nowMillis) = LedgerRepository.Draft(
        vpa = null, payeeName = "p", amountPaise = paise, categoryId = categoryId, note = null, occurredAt = at,
    )

    private suspend fun eventsOf(type: String) = db.gameEventDao().allByIdAsc().filter { it.type == type }

    @Test fun breach_crossing_fires_once_not_on_every_subsequent_txn() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 10_000)) // ₹100 on Food
        repo.saveManualLogged(draft(9_000), breachedAtLogging = false)     // 90 ≤ 100: no cross
        assertEquals(0, eventsOf("budget.breached").size)
        repo.saveManualLogged(draft(2_000), breachedAtLogging = true)      // 90 → 110: crosses
        assertEquals(1, eventsOf("budget.breached").size)
        repo.saveManualLogged(draft(1_000), breachedAtLogging = true)      // 110 → 120: already past, no dup
        assertEquals(1, eventsOf("budget.breached").size)
    }

    @Test fun raising_the_budget_can_legitimately_recross() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 10_000))
        repo.saveManualLogged(draft(11_000), breachedAtLogging = true)     // 0 → 110 vs 100: cross #1
        db.budgetDao().deleteForCategory(1, month)
        db.budgetDao().insert(BudgetEntity(categoryId = 1, month = month, amountPaise = 20_000)) // raised to ₹200
        repo.saveManualLogged(draft(10_000), breachedAtLogging = true)     // 110 → 210 vs 200: cross #2
        assertEquals(2, eventsOf("budget.breached").size)
    }

    @Test fun confirm_path_fires_crossings_when_spend_materializes() = runBlocking {
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = 10_000))
        val uuid = repo.savePending(draft(11_000), breachedAtLogging = true)
        assertEquals(0, eventsOf("budget.breached").size)                  // pending ≠ spent
        repo.confirm(uuid)
        assertEquals(1, eventsOf("budget.breached").size)
    }

    @Test fun backdated_past_month_txn_emits_no_weather() = runBlocking {
        val lastMonth = YearMonth.now(zone).minusMonths(1)
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = lastMonth.toString(), amountPaise = 1_000))
        val at = lastMonth.atDay(10).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        repo.saveManualLogged(draft(5_000, at = at), breachedAtLogging = true)
        assertEquals(0, eventsOf("budget.breached").size)
        assertEquals(1, eventsOf("transaction.logged").size)               // the seed still plants
    }

    @Test fun pace_warning_crossing_fires_without_breach() = runBlocking {
        // Big budget so breach is far: allowance today = budget * day/days * 1.15
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = month, amountPaise = 3_000_000))
        val (day, days) = repo.dayAndLengthOf(nowMillis)
        val allowance = com.expensegarden.app.gate.GateEvaluator.paceAllowancePaise(3_000_000, day, days)
        repo.saveManualLogged(draft(allowance + 1), breachedAtLogging = false)  // 0 → allowance+1: crosses pace
        assertEquals(1, eventsOf("budget.pace_warning").size)
        assertEquals(0, eventsOf("budget.breached").size)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.CrossingEventTest`
Expected: tests FAIL — `budget.breached` count is 0 everywhere (emission not implemented; compile succeeds since Task 8/9 added the queries).

- [x] **Step 3: Implement emission in LedgerRepository.kt**

Add import `com.expensegarden.app.gate.GateEvaluator`. Extend the two LOGGED transitions — in `save(...)`, after the existing `gameEventDao().insert(loggedEvent(uuid))` line inside the transaction, and in `confirm(...)` likewise:

```kotlin
    suspend fun confirm(uuid: String) {
        db.withTransaction {
            db.transactionDao().setStatus(uuid, TxnStatus.LOGGED)
            db.gameEventDao().insert(loggedEvent(uuid))
            db.transactionDao().byUuid(uuid)?.let { emitCrossings(it) }
        }
    }
```

```kotlin
            if (status == TxnStatus.LOGGED) {
                db.gameEventDao().insert(loggedEvent(uuid))
                db.transactionDao().byUuid(uuid)?.let { emitCrossings(it) }
            }
```

New private members:

```kotlin
    /** Weather events for the live month only (spec §3/§4): emit when this txn moved a scope's
     *  spend from ≤ threshold to > threshold. Same-month dedup is free — a later txn starts past the line. */
    private suspend fun emitCrossings(txn: TransactionEntity) {
        val txnMonth = monthKeyOf(txn.occurredAt)
        if (txnMonth != currentMonthKey()) return
        val budgets = db.budgetDao().allForMonth(txnMonth)
        if (budgets.isEmpty()) return
        val tree = CategoryTree(db.categoryDao().all())
        val chain = tree.ancestorChain(txn.categoryId).toSet()
        val (from, to) = boundsOfMonth(txnMonth)
        val leafSums = db.transactionDao().loggedSumsByCategory(from, to).associate { it.categoryId to it.totalPaise }
        val rolled = tree.rollupSums(leafSums)
        val (day, days) = dayAndLengthOf(txn.occurredAt)

        for (b in budgets) {
            val affected = b.categoryId == null || b.categoryId in chain
            if (!affected) continue
            val after = if (b.categoryId == null) leafSums.values.sum() else rolled[b.categoryId] ?: 0L
            val before = after - txn.amountPaise
            if (before <= b.amountPaise && after > b.amountPaise) {
                db.gameEventDao().insert(crossingEvent("budget.breached", txnMonth, b, after, txn.uuid, allowancePaise = null))
                continue
            }
            val allowance = GateEvaluator.paceAllowancePaise(b.amountPaise, day, days)
            if (before <= allowance && after > allowance) {
                db.gameEventDao().insert(crossingEvent("budget.pace_warning", txnMonth, b, after, txn.uuid, allowancePaise = allowance))
            }
        }
    }

    private fun crossingEvent(
        type: String, month: String, budget: BudgetEntity, spentPaise: Long, txnUuid: String, allowancePaise: Long?,
    ): GameEventEntity {
        val payload = JSONObject()
            .put("month", month)
            .put("categoryId", budget.categoryId ?: JSONObject.NULL)
            .put("budgetPaise", budget.amountPaise)
            .put("spentPaise", spentPaise)
            .put("txnUuid", txnUuid)
        allowancePaise?.let { payload.put("allowancePaise", it) }
        return GameEventEntity(type = type, payloadJson = payload.toString(), transactionUuid = txnUuid, createdAt = now())
    }
```

- [x] **Step 4: Run to verify pass**

Run: the Step 2 command. Expected: 5 tests passed.
Then full instrumented suite: `./gradlew connectedDebugAndroidTest` — green.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/androidTest/java/com/expensegarden/app/data/CrossingEventTest.kt
git commit -m "feat: budget crossing events - breached and pace_warning emitted atomically with logging"
```

---

### Task 11: Regret tagging — repository + events

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt`
- Create: `app/src/androidTest/java/com/expensegarden/app/data/RegretTest.kt`

- [x] **Step 1: Write the failing tests**

```kotlin
package com.expensegarden.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegretTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: LedgerRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .addCallback(SeedCallback).allowMainThreadQueries().build()
        repo = LedgerRepository(db)
    }

    @After fun teardown() = db.close()

    private suspend fun logOne(): String = repo.saveManualLogged(
        LedgerRepository.Draft(vpa = null, payeeName = "p", amountPaise = 5_000, categoryId = 103,
            note = null, occurredAt = System.currentTimeMillis()),
        breachedAtLogging = false,
    )

    private suspend fun eventsOf(type: String) = db.gameEventDao().allByIdAsc().filter { it.type == type }

    @Test fun tagging_regret_updates_row_and_emits_event() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.REGRET)
        assertEquals(Regret.REGRET, db.transactionDao().byUuid(uuid)!!.regret)
        assertEquals(1, eventsOf("transaction.regretted").size)
    }

    @Test fun clearing_regret_emits_cleared_event() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.REGRET)
        repo.setRegret(uuid, Regret.WORTH_IT)
        assertEquals(Regret.WORTH_IT, db.transactionDao().byUuid(uuid)!!.regret)
        assertEquals(1, eventsOf("transaction.regretted").size)
        assertEquals(1, eventsOf("transaction.regret_cleared").size)
    }

    @Test fun worth_it_from_unrated_emits_nothing() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.WORTH_IT)
        assertEquals(0, eventsOf("transaction.regretted").size)
        assertEquals(0, eventsOf("transaction.regret_cleared").size)
    }

    @Test fun retagging_same_value_is_a_no_op() = runBlocking {
        val uuid = logOne()
        repo.setRegret(uuid, Regret.REGRET)
        repo.setRegret(uuid, Regret.REGRET)
        assertEquals(1, eventsOf("transaction.regretted").size)
    }
}
```

- [x] **Step 2: Run to verify failure**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.RegretTest`
Expected: compile FAILED — `unresolved reference: setRegret` on the repository (DAO has it; repo doesn't yet).

- [x] **Step 3: Implement in LedgerRepository.kt**

```kotlin
    /** Regret is re-taggable; only transitions touching REGRET leave history (spec §4).
     *  Never punishes the log — this feeds garden rendering only. */
    suspend fun setRegret(uuid: String, value: Regret) {
        db.withTransaction {
            val txn = db.transactionDao().byUuid(uuid) ?: return@withTransaction
            if (txn.regret == value) return@withTransaction
            db.transactionDao().setRegret(uuid, value)
            if (value == Regret.REGRET) {
                val payload = JSONObject().put("uuid", uuid)
                    .put("categoryId", txn.categoryId).put("amountPaise", txn.amountPaise)
                db.gameEventDao().insert(GameEventEntity(
                    type = "transaction.regretted", payloadJson = payload.toString(),
                    transactionUuid = uuid, createdAt = now(),
                ))
            } else if (txn.regret == Regret.REGRET) {
                db.gameEventDao().insert(GameEventEntity(
                    type = "transaction.regret_cleared", payloadJson = JSONObject().put("uuid", uuid).toString(),
                    transactionUuid = uuid, createdAt = now(),
                ))
            }
        }
    }
```

- [x] **Step 4: Run to verify pass** — the Step 2 command. Expected: 4 tests passed.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/androidTest/java/com/expensegarden/app/data/RegretTest.kt
git commit -m "feat: regret tagging - retaggable rating with append-only regret events"
```

---

### Task 12: MainViewModel rework — verdict-based gate, header state, chips, regret

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt` (gate dialog title only)
- Modify: `app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt` (compile fix for header state; visual work in Task 15)

No new unit tests here — the logic is in already-tested pure units; this task is wiring. The build plus existing suites are the check.

- [x] **Step 1: Rework MainViewModel.kt**

Replace `GatePrompt` and the affected members:

```kotlin
data class GatePrompt(val severity: Severity, val quip: String, val scopeLabel: String?)

/** Home header: null while Room's first emission is in flight (loading skeleton). */
data class HomeHeader(val spentPaise: Long, val overallBudgetPaise: Long?, val hint: Severity)
```

The old `monthSpent` and `monthBudget` StateFlows are DELETED — `homeHeader` replaces both. The `flow {}` wrapper matters: `WhileSubscribed` restarts re-run the builder, re-deriving the month key, which is the spec §5 staleness fix (Task 9's interim wiring still froze the key at property init).

```kotlin
    val homeHeader: StateFlow<HomeHeader?> =
        flow {
            val monthKey = ledger.currentMonthKey()   // fresh on every (re)subscription
            emitAll(
                combine(
                    ledger.observeMonthSpent(monthKey),
                    container.db.budgetDao().observeAllForMonth(monthKey),
                ) { spent, budgets ->
                    val overall = budgets.firstOrNull { it.categoryId == null }?.amountPaise
                    val (day, days) = ledger.today()
                    HomeHeader(spent, overall, GateEvaluator.evaluate(spent, overall, 0L, day, days))
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val chipCategories: StateFlow<List<CategoryEntity>> =
        combine(
            container.db.categoryDao().observeAll(),
            container.db.transactionDao().observeCategoryUsageSince(
                System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            ),
        ) { cats, usage ->
            ChipOrder.topChips(cats, usage.associate { it.categoryId to it.uses })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Replace `prepareGate` and `saveManualFromDraft` to use the verdict (and delete `setOverallBudget` — it moves to DashboardViewModel in Task 13):

```kotlin
    /** Compute severity + quip. OK never shows a dialog (silence rule at the gate). */
    suspend fun prepareGate(amountPaise: Long): GatePrompt {
        val d = draft.value
        val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt)
        val quip = if (verdict.severity == Severity.OK) "" else container.quips.pick(verdict.severity)
        val label = verdict.offender?.takeIf { it.categoryId != null }?.label
        return GatePrompt(verdict.severity, quip, label)
    }

    fun saveManualFromDraft(amountPaise: Long) {
        val d = draft.value
        viewModelScope.launch {
            val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt)
            ledger.saveManualLogged(d.toRepoDraft(amountPaise), breachedAtLogging = verdict.severity == Severity.BREACH)
        }
    }

    fun setRegret(uuid: String, value: Regret) = viewModelScope.launch { ledger.setRegret(uuid, value) }

    fun setDraftDate(epochMillis: Long) {
        draft.value = draft.value.copy(occurredAt = epochMillis)
    }
```

`savePendingFromDraft` keeps its signature — EntryScreen still passes `prompt.severity`. Imports to add: `com.expensegarden.app.data.Regret`, `com.expensegarden.app.stats.ChipOrder`, `kotlinx.coroutines.flow.combine`, `kotlinx.coroutines.flow.emitAll`, `kotlinx.coroutines.flow.flow` (`GateEvaluator` stays imported — homeHeader uses it).

- [x] **Step 2: EntryScreen gate dialog title names the offender**

Replace the `title = { ... }` line in the gate `AlertDialog`:

```kotlin
            title = {
                val base = if (prompt.severity == Severity.BREACH) "Over budget" else "Ahead of pace"
                Text(prompt.scopeLabel?.let { "$base — $it" } ?: base)
            },
```

- [x] **Step 3: HomeScreen minimal compile fix**

`vm.monthSpent`/`vm.monthBudget` no longer exist and `vm.setOverallBudget` is gone. Interim wiring only (Task 15 does the real skeleton): replace the two collections at the top with

```kotlin
    val header by vm.homeHeader.collectAsState()
```

point the odometer `AnimatedContent` at `targetState = header?.spentPaise ?: 0L`, and delete the `budgetDialogOpen` state + budget `AlertDialog` block + the `TextButton` that opened it, replacing the button with a plain line:

```kotlin
                    val b = header?.overallBudgetPaise
                    Text(
                        if (b == null) "No budget set" else "Budget: ${Money.display(b)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
```

- [x] **Step 4: Build + full JVM suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all JVM tests green (now 46: 22 from 1A + 24 new).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt
git commit -m "feat: viewmodel rework - scoped gate verdicts, home header state, chips, regret"
```

---

### Task 13: DashboardViewModel + DashboardScreen + navigation

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt`
- Create: `app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt`
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt` (card tap-through)

- [x] **Step 1: DashboardViewModel.kt**

```kotlin
package com.expensegarden.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.expensegarden.app.AppContainer
import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.stats.MonthStats
import com.expensegarden.app.stats.MonthStatsFolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val ledger = container.ledger

    /** null = loading (skeleton). flow{} wrapper: re-subscription re-derives the month key (spec §5 staleness fix). */
    val stats: StateFlow<MonthStats?> =
        flow {
            val monthKey = ledger.currentMonthKey()
            val (from, to) = ledger.boundsOfMonth(monthKey)
            emitAll(
                combine(
                    container.db.categoryDao().observeAll(),
                    container.db.transactionDao().observeLoggedSumsByCategory(from, to),
                    container.db.budgetDao().observeAllForMonth(monthKey),
                ) { cats, sums, budgets ->
                    val (day, days) = ledger.today()
                    MonthStatsFolder.fold(cats, sums.associate { it.categoryId to it.totalPaise }, budgets, day, days)
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** amountPaise null = clear. categoryId null = overall. Same delete+insert idiom as 1A. */
    fun setBudget(categoryId: Long?, amountPaise: Long?) {
        viewModelScope.launch {
            val monthKey = ledger.currentMonthKey()   // at call time, not VM birth
            container.db.withTransaction {
                if (categoryId == null) container.db.budgetDao().deleteOverallForMonth(monthKey)
                else container.db.budgetDao().deleteForCategory(categoryId, monthKey)
                if (amountPaise != null && amountPaise > 0) {
                    container.db.budgetDao().insert(BudgetEntity(categoryId = categoryId, month = monthKey, amountPaise = amountPaise))
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DashboardViewModel(container) as T
        }
    }
}
```

- [x] **Step 2: DashboardScreen.kt**

```kotlin
package com.expensegarden.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.expensegarden.app.core.Money
import com.expensegarden.app.gate.Severity
import com.expensegarden.app.stats.ScopeStat

/** Which budget the dialog edits: overall (null) or a category. */
private data class BudgetTarget(val categoryId: Long?, val name: String, val currentPaise: Long?)

@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val stats by vm.stats.collectAsState()
    var target by remember { mutableStateOf<BudgetTarget?>(null) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This month", style = MaterialTheme.typography.headlineSmall)

        val s = stats
        if (s == null) {
            // Skeleton until Room's first emission — same trick as home.
            Card(Modifier.fillMaxWidth().height(120.dp).alpha(0.3f)) {}
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AnimatedContent(
                        targetState = s.spentPaise,
                        transitionSpec = {
                            (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 } +
                                fadeIn(spring(stiffness = Spring.StiffnessMedium))) togetherWith
                                (slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it / 2 } +
                                    fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                        },
                        label = "dashSpent",
                    ) { spent -> Text(Money.display(spent), style = MaterialTheme.typography.headlineMedium) }

                    TextButton(onClick = { target = BudgetTarget(null, "overall", s.overallBudgetPaise) }) {
                        Text(if (s.overallBudgetPaise == null) "Set overall budget"
                             else "Budget: ${Money.display(s.overallBudgetPaise!!)}")
                    }
                    Text("Projected: ${Money.display(s.projectedPaise)} by month end", style = MaterialTheme.typography.bodyMedium)
                    s.perDayPaise?.let {
                        Text("${Money.display(it)}/day keeps you under", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(severityLine(s.overallSeverity), color = severityColor(s.overallSeverity),
                        style = MaterialTheme.typography.labelMedium)
                }
            }

            Text("Budgets", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(s.rows, key = { it.categoryId ?: -1L }) { row ->
                    CategoryRow(row) { target = BudgetTarget(row.categoryId, row.name, row.budgetPaise) }
                }
            }
        }
    }

    target?.let { t ->
        var text by remember(t) { mutableStateOf(t.currentPaise?.let { Money.intentAmount(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(if (t.categoryId == null) "Overall budget" else "${t.name} budget") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Amount (₹)") }) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setBudget(t.categoryId, Money.parseToPaise(text))
                    target = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (t.currentPaise != null) {
                        TextButton(onClick = { vm.setBudget(t.categoryId, null); target = null }) { Text("Clear") }
                    }
                    TextButton(onClick = { target = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun CategoryRow(row: ScopeStat, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (row.indent) 16.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                row.budgetPaise?.let { "${Money.display(row.spentPaise)} / ${Money.display(it)}" }
                    ?: Money.display(row.spentPaise),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        row.budgetPaise?.let { budget ->
            LinearProgressIndicator(
                progress = { (row.spentPaise.toFloat() / budget).coerceIn(0f, 1f) },
                color = severityColor(row.severity),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun severityLine(s: Severity) = when (s) {
    Severity.OK -> "on pace"
    Severity.PACE_WARNING -> "ahead of pace"
    Severity.BREACH -> "over budget"
}

@Composable
private fun severityColor(s: Severity): Color = when (s) {
    Severity.OK -> MaterialTheme.colorScheme.primary
    Severity.PACE_WARNING -> MaterialTheme.colorScheme.tertiary
    Severity.BREACH -> MaterialTheme.colorScheme.error
}
```

Note: `LinearProgressIndicator(progress = { ... })` is the lambda overload current in this BOM; if the build flags it deprecated-vs-missing, use the lambda form shown — do NOT chase other overloads.

- [x] **Step 3: Wire navigation in MainActivity.kt and the tap-through in HomeScreen.kt**

MainActivity: add a `dashVm` next to `vm` in the activity...

```kotlin
    private val dashVm: DashboardViewModel by viewModels {
        DashboardViewModel.factory((application as GardenApp).container)
    }
```

...pass it through `GardenNav(vm, dashVm)`, add the route inside the NavHost (default fades apply — no per-route transitions), and hand Home the callback:

```kotlin
        composable("dashboard") { DashboardScreen(vm = dashVm) }
```

```kotlin
            HomeScreen(
                vm = vm,
                onScan = { ... unchanged ... },
                onManual = { ... unchanged ... },
                onOpenDashboard = { nav.navigate("dashboard") },
            )
```

HomeScreen: add the parameter `onOpenDashboard: () -> Unit` and make the month card clickable:

```kotlin
            Card(Modifier.fillMaxWidth().clickable(onClick = onOpenDashboard)) {
```

(Import `androidx.compose.foundation.clickable` and `com.expensegarden.app.ui.DashboardViewModel`/import updates in MainActivity as the compiler directs.)

- [x] **Step 4: Build, install, smoke on the emulator**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug && adb shell am start -n com.expensegarden.app/.MainActivity`
Expected: app opens. Tap the "This month" card → dashboard fades in showing spent total (₹105 from the 1A smoke data), "Set overall budget" if none for this month, parent category rows with the July spends under Food & Drinks. Set a category budget via a row tap → progress bar appears. `adb exec-out screencap -p > /tmp/dash.png` for the record.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt app/src/main/java/com/expensegarden/app/MainActivity.kt app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt
git commit -m "feat: dashboard - month stats, pace, per-category budgets with progress"
```

---

### Task 14: EntryScreen — quick-pick chips + backdating

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt`

- [x] **Step 1: Replace the ExposedDropdownMenuBox block with chips + sheet**

Delete the whole `ExposedDropdownMenuBox { ... }` block and the `categoryMenuOpen` state; add in their place (plus a `var allCategoriesOpen by remember { mutableStateOf(false) }`):

```kotlin
        val chips by vm.chipCategories.collectAsState()
        val selectedId = draft.categoryId
        Text("Category", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Selected category always visible, even when outside the top-8 (e.g. payee prefill).
            val shown = if (selectedId != null && chips.none { it.id == selectedId })
                chips + categories.filter { it.id == selectedId } else chips
            shown.forEach { cat ->
                FilterChip(
                    selected = cat.id == selectedId,
                    onClick = { vm.draft.value = draft.copy(categoryId = cat.id) },
                    label = { Text(cat.name) },
                )
            }
            FilterChip(selected = false, onClick = { allCategoriesOpen = true }, label = { Text("All…") })
        }

        if (allCategoriesOpen) {
            ModalBottomSheet(onDismissRequest = { allCategoriesOpen = false }) {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(categories, key = { it.id }) { cat ->
                        Text(
                            text = if (cat.parentId == null) cat.name else "    ${cat.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.draft.value = draft.copy(categoryId = cat.id)
                                    allCategoriesOpen = false
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
```

New imports: `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.FlowRow`, `androidx.compose.foundation.layout.PaddingValues`, `androidx.compose.foundation.lazy.LazyColumn`, `androidx.compose.foundation.lazy.items`, `androidx.compose.material3.FilterChip`, `androidx.compose.material3.ModalBottomSheet`. Add `@OptIn(ExperimentalLayoutApi::class)` alongside the existing `@OptIn(ExperimentalMaterial3Api::class)` (import `androidx.compose.foundation.layout.ExperimentalLayoutApi`). Remove the now-unused dropdown imports (`DropdownMenuItem`, `ExposedDropdownMenuBox`, `ExposedDropdownMenuDefaults`, `MenuAnchorType`).

- [x] **Step 2: Add the backdating row (manual entries only)**

After the "Paid to" field's `if (!draft.fromScan) { ... }` block, extend that same block (still inside it, after the payee field):

```kotlin
            var datePickerOpen by remember { mutableStateOf(false) }
            val zone = remember { ZoneId.systemDefault() }
            val dateFmt = remember { DateTimeFormatter.ofPattern("dd MMM yyyy") }
            OutlinedButton(onClick = { datePickerOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("On ${dateFmt.format(Instant.ofEpochMilli(draft.occurredAt).atZone(zone))}")
            }
            if (datePickerOpen) {
                val todayUtc = LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val state = rememberDatePickerState(
                    initialSelectedDateMillis = Instant.ofEpochMilli(draft.occurredAt).atZone(zone)
                        .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    selectableDates = object : SelectableDates {
                        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
                    },
                )
                DatePickerDialog(
                    onDismissRequest = { datePickerOpen = false },
                    confirmButton = {
                        TextButton(onClick = {
                            state.selectedDateMillis?.let { utc ->
                                // Picker returns UTC midnight; pin the txn to local noon of that date
                                // (steers clear of DST/midnight month-boundary weirdness).
                                val local = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                                    .atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                vm.setDraftDate(local)
                            }
                            datePickerOpen = false
                        }) { Text("OK") }
                    },
                ) { DatePicker(state = state) }
            }
```

New imports: `androidx.compose.material3.DatePicker`, `androidx.compose.material3.DatePickerDialog`, `androidx.compose.material3.OutlinedButton`, `androidx.compose.material3.SelectableDates`, `androidx.compose.material3.rememberDatePickerState`, `java.time.Instant`, `java.time.LocalDate`, `java.time.ZoneId`, `java.time.ZoneOffset`, `java.time.format.DateTimeFormatter`.

- [x] **Step 3: Build + smoke**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug`
Then on the emulator: Log manually → chips render in one/two rows, tap "All…" → sheet with full indented list, pick a child; date button shows today, pick yesterday; save with ₹15 → home list shows it dated yesterday. Verify by `adb shell "run-as com.expensegarden.app sqlite3 /data/data/com.expensegarden.app/databases/garden.db 'SELECT amountPaise, occurredAt FROM txn ORDER BY createdAt DESC LIMIT 1'"` — occurredAt falls inside yesterday (local noon).
Expected: all of the above; no IME required for category selection.

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt
git commit -m "feat: entry - quick-pick category chips with all-sheet, manual backdating"
```

---

### Task 15: HomeScreen — skeleton, severity hint, regret dialog

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt`

- [x] **Step 1: Header uses HomeHeader state (skeleton + hint)**

Replace the month-card contents: collect `val header by vm.homeHeader.collectAsState()` instead of the separate `monthSpent`/`budget` collections, and render:

```kotlin
            Card(Modifier.fillMaxWidth().clickable(onClick = onOpenDashboard)) {
                val h = header
                if (h == null) {
                    // Skeleton: fixed-height quiet block until Room's first emission (kills the ₹0.00 flash).
                    Column(Modifier.padding(16.dp).fillMaxWidth().height(72.dp).alpha(0.3f)) {
                        Text("This month", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        Text("This month", style = MaterialTheme.typography.labelMedium)
                        AnimatedContent(
                            targetState = h.spentPaise,
                            transitionSpec = {
                                (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 2 } +
                                    fadeIn(spring(stiffness = Spring.StiffnessMedium))) togetherWith
                                    (slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { -it / 2 } +
                                        fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                            },
                            label = "monthSpent",
                        ) { spent -> Text(Money.display(spent), style = MaterialTheme.typography.headlineMedium) }
                        Text(
                            h.overallBudgetPaise?.let { "Budget: ${Money.display(it)} · ${hintLine(h.hint)}" }
                                ?: "Tap for the dashboard",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
```

With a small helper at file bottom:

```kotlin
private fun hintLine(s: Severity) = when (s) {
    Severity.OK -> "on pace"
    Severity.PACE_WARNING -> "ahead of pace"
    Severity.BREACH -> "over budget"
}
```

Delete the now-unused `monthSpent`/`budget` collections and any leftover budget-dialog remnants from Task 12's interim state.

- [x] **Step 2: Regret dialog on recent rows**

Make each recent row clickable and add the dialog. Row change:

```kotlin
                items(recent, key = { it.uuid }) { row ->
                    Row(
                        Modifier.fillMaxWidth().animateItem().clickable { regretTarget = row },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(row.payeeName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${row.categoryName} · ${dateFmt.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.systemDefault()))}" +
                                    when (row.regret) {
                                        Regret.REGRET -> " · regret"
                                        Regret.WORTH_IT -> " · worth it"
                                        Regret.UNRATED -> ""
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(Money.display(row.amountPaise), style = MaterialTheme.typography.bodyLarge)
                    }
                }
```

State + dialog (state near the other `remember`s; dialog after the pending-card block):

```kotlin
    var regretTarget by remember { mutableStateOf<TxnRow?>(null) }
```

```kotlin
    regretTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { regretTarget = null },
            title = { Text("${Money.display(row.amountPaise)} — ${row.payeeName}") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = row.regret == Regret.WORTH_IT,
                        onClick = { vm.setRegret(row.uuid, Regret.WORTH_IT); regretTarget = null },
                        label = { Text("Worth it") },
                    )
                    FilterChip(
                        selected = row.regret == Regret.REGRET,
                        onClick = { vm.setRegret(row.uuid, Regret.REGRET); regretTarget = null },
                        label = { Text("Regret") },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { regretTarget = null }) { Text("Close") } },
        )
    }
```

New imports: `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.height`, `androidx.compose.material3.FilterChip`, `androidx.compose.ui.draw.alpha`, `com.expensegarden.app.data.Regret`, `com.expensegarden.app.data.TxnRow`, `com.expensegarden.app.gate.Severity`. (`TransactionEntity` import stays — the pending card still uses it.)

- [x] **Step 3: Build + smoke**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew installDebug`
On the emulator: cold-start the app (`adb shell am force-stop com.expensegarden.app` first) — the header shows the quiet skeleton, then the amount springs in; tap a recent row → dialog; mark "Regret" → row line gains "· regret". Verify the event: `adb shell "run-as com.expensegarden.app sqlite3 /data/data/com.expensegarden.app/databases/garden.db \"SELECT type FROM game_event ORDER BY id DESC LIMIT 1\""` → `transaction.regretted`.
Expected: all of the above.

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/HomeScreen.kt
git commit -m "feat: home - loading skeleton, budget hint, regret tagging on recent rows"
```

---

### Task 16: Full regression + 1B verification sweep

**Files:**
- Modify: `docs/superpowers/plans/2026-07-06-phase1b-budgets-dashboard.md` (tick checkboxes, log any amendments)
- Modify: memory `expense-garden-phase1a-status.md` (1B status entry)

- [x] **Step 1: Full suites**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew testDebugUnitTest && ./gradlew connectedDebugAndroidTest`
Expected: BUILD SUCCESSFUL twice. JVM: 46 tests (22 old + 24 new). Instrumented: 20 (4 old + 16 new: 1 migration, 3 budget-dao, 3 gate-scope, 5 crossing, 4 regret). Zero failures.

- [x] **Step 2: Emulator E2E sweep (scripted where possible, uiautomator coordinates — not blind taps)**

1. Fresh install over existing data (`./gradlew installDebug` — NOT a wipe): app opens, migration v1→v2 runs on the real `garden.db`; home renders the 1A data. Check: `adb shell "run-as com.expensegarden.app sqlite3 /data/data/com.expensegarden.app/databases/garden.db 'PRAGMA user_version'"` → `2`.
2. Dashboard: totals match home; set a Food & Drinks budget below current Food spend → row turns error-tinted, header hint updates.
3. Manual entry via chips crossing that category budget → check `game_event` gains `budget.breached` with `categoryId = 1`.
4. Gate path (scan flow can be simulated by the QR poster leg when it resumes — for now: manual verification that `prepareGate` titles carry the category name is deferred to the Task-12 resume, note it).
5. Regret-tag the new txn; verify event.
6. Reboot the emulator (`adb reboot`), reopen: data + budgets survive; skeleton shows on cold start, no ₹0.00 flash.

Expected: every step checks out; any mismatch = STOP and report before proceeding.

- [x] **Step 3: Tick all checkboxes in this plan, add an "Execution amendments" entry for anything that deviated, update memory status**

- [x] **Step 4: Final commit**

```bash
git add docs/superpowers/plans/2026-07-06-phase1b-budgets-dashboard.md
git commit -m "docs: 1b plan executed - checkboxes ticked, amendments logged"
```

---

## Execution amendments

(Log deviations here as they happen — pattern proven in 1A.)

1. **Task 7 needed schema assets wiring (plan gap).** `MigrationTestHelper` failed with `FileNotFoundException: …assets… 1.json` — exported schemas must be exposed to androidTest via `sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }` in `app/build.gradle.kts` (official Room migration-testing setup, linked from the error). One build-file line; no dependency or version change.
2. **Task 14's All-sheet needed grouped ordering (plan bug).** The plan's sheet iterated `categories` raw — the DAO's `isNecessity DESC, name` order scatters indented children away from their parents ("Fuel" under "Family"). Fixed with a parent-then-children (seed-id) ordering computed in the composable; chips were already correct via `ChipOrder`.
3. **Task 16's live-DB migration check is moot (plan assumption wrong).** `connectedDebugAndroidTest` uninstalls the app after each run (AGP default), deleting app data — the 1A-era on-device `garden.db` (v1, ₹105 smoke data) was wiped by today's first instrumented run. The app now starts with a fresh v2 seed (verified: 21 categories + 10 quips, `user_version=2`, all ledgers empty). Migration correctness remains fully proven by `MigrationTest` (constructs real v1 → migrates → validates schema + rows). Task 16 Step 2.1 becomes: verify fresh seed + `user_version=2`; smoke data gets recreated through the UI during Tasks 13–15.
4. **Task 5 test expectation corrected (plan arithmetic bug).** The plan asserted chai's row severity `OK` for ₹600 spent against its own ₹1000 budget at day 15/30 ("fine at day 15") — wrong: the pace line is 1000 × 15/30 × 1.15 = ₹575, so ₹600 is `PACE_WARNING`. `MonthStatsFolder` behavior is correct per spec; the committed test asserts `PACE_WARNING`. No production-code change.

## Deferred to later plans (recorded so they aren't forgotten)

- Gate-dialog category-title verification on the live scan path → rides the Task-12 (1A) resume alongside the QR poster leg.
- R8 + baseline profile + phone Macrobenchmark → needs the physical device (Task-12 resume).
- `budget.pace_warning`/`budget.breached` fold semantics (weather rendering) → 1C's job; 1B only records honest history.
- Month-history browsing on the dashboard → post-1E (needs imported history to be worth it).
