# Phase 1B Design — Per-Category Budgets + Dashboard

**Date:** 2026-07-06 · **Status:** awaiting user approval · **Parent spec:** `2026-07-03-expense-garden-design.md`
**Inputs:** parent spec §2 (goals), §8.3 (stats), §9.2–9.3 (events, weed rule), §11 (data model); 1A code as built (`GateEvaluator`, `BudgetEntity`, `LedgerRepository`); fluidity backlog from the 1A verification log.

## 1. Scope

**In:** per-category budgets (Room migration v1→v2, gate + weed-rule upgrade, crossing events), dashboard screen (month stats, category breakdown, pace), category quick-pick chips, cold-start loading states, regret tagging, manual-entry backdating.

**Deferred, with reasons:**
- R8 + baseline profile → needs a physical device for profile generation/verification; rides the Task 12 resume.
- Month-history browsing, charts beyond progress bars, budget rollover, transaction edit/delete, category CRUD → not needed for the 1B loop; category taxonomy stays seed-only until the FC-import mapping (1E) forces the question.
- `CHECK` constraints on money columns → Room's entity annotations cannot express them; they land in the Postgres DDL (Phase 2) as planned. Recorded so it reads as a decision, not an omission.

## 2. Decisions at a glance

| Topic | Decision | Alternatives rejected |
|---|---|---|
| Budget semantics | **Subtree rollup**: a budget on category C covers C + all descendants; spend rolls up | Flat per-category (breaks "Food covers all food"); nearest-ancestor-only (silently ignores parent budgets) |
| Gate composition | Evaluate **overall + every budgeted scope on the txn category's ancestor chain**; worst severity wins; dialog names the most specific offending scope | Overall-only (1A status quo — contradicts spec §9.3); category-only (loses the total-budget backstop) |
| `breachedAtLogging` | True iff **any evaluated scope breached** (category chain *or* overall) | Category-chain-only (would retroactively disagree with all 1A rows, which used overall) |
| Backdated entries | Evaluated against **the month of `occurredAt`** (that month's budgets + that month's spend incl. this txn) | Always-current-month (plants weeds from stale context) |
| Crossing events | Emit `budget.breached` / `budget.pace_warning` when a logged txn moves a scope's spend from ≤ threshold to > threshold; **only for the current month** | Emitting for backdated past months (weather is about the live month; past months are album material) |
| Dashboard placement | **Separate nav destination**; home's "This month" card is the tap-through | Expanding home (fights the garden for the home surface in 1C) |
| Charts in 1B | Stock `LinearProgressIndicator` bars, severity-tinted | Chart library (new dep for a placeholder screen); hand-rolled Canvas (Canvas debuts in 1C where it earns its complexity) |
| ViewModel split | New `DashboardViewModel`; `MainViewModel` keeps capture/home | Growing MainViewModel past ~220 lines of mixed concerns |
| New dependency | `androidx.room:room-testing` (androidTest only, existing 2.6.1 version ref) — for `MigrationTestHelper` | Skipping migration tests (first migration of a source-of-truth DB deserves proof) |

## 3. Budget model & gate semantics

**Scope** = one budget row: `(categoryId NULL = overall | category C, month, amountPaise)`. `UNIQUE(categoryId, month)` already enforced since v1.

**Rollup rule.** `spend(scope C, month) = Σ amountPaise` of LOGGED txns in `month` whose category is in `subtree(C)`. The seed taxonomy is two levels, but the fold is written for arbitrary depth (pure Kotlin over the `parentId` map — no recursive SQL).

**Gate evaluation** (QR path, before payment): for candidate amount A in category K, evaluate `GateEvaluator.evaluate(...)` — unchanged 1A pure function, `PACE_GRACE = 1.15`, day-proportional — once per scope in `{overall} ∪ {budgeted scopes on K's ancestor chain, K included}`. **Worst severity wins.** The gate dialog title names the most specific scope at that worst severity ("Over budget — Eating out"); quips remain severity-keyed (category-family tone is 1D).

**Manual path** stays gate-less (post-hoc, straight to LOGGED); the same evaluation runs silently to set `breachedAtLogging`.

**`breachedAtLogging`** = any evaluated scope at BREACH. Overall stays in the definition deliberately: every 1A row was computed from overall, so 1B keeps existing data semantically consistent, and a discretionary spend during a total-budget breach is honestly a weed (§9.3 "discretionary past budget").

**Backdating.** Evaluation runs against the `occurredAt` month: its budget rows, its LOGGED spend, plus this txn. `dayOfMonth/daysInMonth` for pace use the txn's date. Crossing events (below) are additionally suppressed unless the `occurredAt` month is the current month.

## 4. game_event additions (append-only; atomic with the LOGGED transition)

Emitted inside the same `withTransaction` that makes a txn LOGGED (`saveManualLogged` and `confirm` — 1A already emits `transaction.logged` there). New types:

| Type | Payload | When |
|---|---|---|
| `budget.breached` | `{month, categoryId (null=overall), budgetPaise, spentPaise, txnUuid}` | scope spend crosses `budgetPaise` |
| `budget.pace_warning` | `{month, categoryId, budgetPaise, spentPaise, allowancePaise, txnUuid}` | scope spend crosses that day's pace allowance (and is not also a breach crossing) |
| `transaction.regretted` | `{uuid, categoryId, amountPaise}` | user sets regret = REGRET |
| `transaction.regret_cleared` | `{uuid}` | user moves a REGRET rating to WORTH_IT/UNRATED |

**Dedup stance:** crossing semantics (`spendBefore ≤ threshold < spendAfter`) make same-day duplicates impossible without a budget edit. A raised budget can legitimately produce a second breach crossing in one month; rising daily allowance can produce repeat pace crossings across days. Both are honest history — the 1C fold owns interpretation. `transactionUuid` FK column is set on all txn-anchored events. `gate.dodged` unchanged.

## 5. Data & migration

**Migration v1→v2** (first migration; schema `2.json` committed): recreate `budget` with `FOREIGN KEY (categoryId) REFERENCES category(id) ON DELETE CASCADE` (a budget without its category is meaningless; categories are seed-only so this is belt-and-braces, chosen deliberately per the DB standard), copy rows, recreate the unique index. Verified by an instrumented `MigrationTestHelper` test: v1 data (incl. an overall budget row) survives, and a bogus `categoryId` insert fails post-migration.

**DAO additions:**
- `BudgetDao`: `observeAllForMonth(month)`, scope-keyed upsert (delete+insert inside the caller's transaction — same pattern as 1A's overall), scope-keyed delete.
- `TransactionDao`: per-category LOGGED sums for a month window (`GROUP BY categoryId`); category usage counts over the last 90 days (chips); `observeRecent` row gains `categoryId` + `regret`; `setRegret(uuid, value)`.
- `CategoryDao`: unchanged — rollup happens in pure Kotlin.

**Month-bounds fix (1A cleanup):** `observeMonthSpent()` freezes bounds at flow creation and goes stale across a month boundary. 1B parameterizes month-window queries by an explicit month key supplied at collection/composition time; home and dashboard both use the fixed path.

## 6. `stats/` module (new; pure Kotlin, JVM-tested — spec §6)

- `PaceProjector`: linear projection `spent × daysInMonth / dayOfMonth` (guarded for day 1), projected month-end delta vs budget, remaining per-day allowance `max(0, (budget − spent) / daysLeft)`.
- `MonthStatsFolder`: (categories, per-category sums, budget rows, day/days) → header stats + per-scope rollup rows with severity. Feeds both the dashboard and the gate's scope list so the two can never disagree.
- `GateAggregator` (in `gate/`): scope list → worst severity + most-specific offender. `GateEvaluator` itself is untouched; its 1A tests stand.

## 7. UI

**Home:** "This month" card becomes tappable → dashboard; gains a one-line severity hint ("on pace" / "ahead of pace" / "over budget") from the same `MonthStatsFolder`. Header renders a subdued skeleton until first emission (kills the cold-start ₹0.00 flash). The overall-budget dialog moves to the dashboard header; recent-list rows become tappable → regret dialog.

**Dashboard** (route `"dashboard"`, default fade transitions, rows use `animateItem()`):
- Header card: month spent (odometer `AnimatedContent`, exact values only), overall budget line (tap to set/clear — the dialog relocated from home), pace block: "Projected: ₹X by month end (₹Y over/under)" + "₹Z/day keeps you under", severity-tinted.
- Category list: all parent categories in seed order; children shown indented only when they have spend or a budget this month. Each row: name, rolled-up spend, budget or —, severity-tinted `LinearProgressIndicator` (only when budgeted), tap → set/clear that category's budget (same dialog pattern).

**Quick-pick chips** (EntryScreen, replaces the 21-item dropdown): `FlowRow` of `FilterChip`s — top 8 categories by 90-day usage (LOGGED txn count; seed-order fill when history is thin), plus an "All…" chip opening a `ModalBottomSheet` with the full indented list. No IME interplay (the dropdown's worst failure mode in 1A automation and one-thumb use). Chip ordering is a pure function, JVM-tested.

**Regret dialog** (from a recent-list row): payee, amount, category, date; `Worth it` / `Regret` selectable chips reflecting current state, re-taggable any time; Close. Tagging a necessity is allowed (honest data) but has no weed effect — the §9.3 rule ANDs on discretionary. Never punishes the log: tagging changes garden rendering only, never stats or streaks against the act of logging.

**Backdating** (manual entry only — scan is a live payment, `occurredAt = now`): date row under the amount field → M3 `DatePickerDialog` (`@OptIn`, same as existing M3 experimental usage), future dates blocked, defaults to today.

**Loading states:** nullable-initial `StateFlow`s; skeletons on home header + dashboard header until first emission; lists render empty naturally. Motion vocabulary unchanged (StiffnessMedium fades, existing springs).

## 8. Dependencies

Exactly one, test-only: `androidx.room:room-testing` pinned to the existing `room = "2.6.1"` version ref, `androidTestImplementation`. No runtime dependencies added. Pinned-matrix guardrail otherwise unchanged.

## 9. Testing

**JVM (TDD, red-green per unit):** `GateAggregator` (worst-wins, ancestor-chain composition, overall fallback, flag rule), `PaceProjector` (day 1, last day, no-budget), rollup fold (2-level seed + deeper synthetic tree), chip ordering (ties, thin history).
**Instrumented:** migration v1→v2 (row survival + FK enforcement), per-category sum query, crossing-event emission (fires once per crossing; re-fires after a budget raise; fires on `confirm()`; suppressed for backdated past months), regret transitions + events, scope-keyed budget upsert.
**No-regression bar:** the full 1A suite (22 JVM + 4 instrumented) stays green untouched — `GateEvaluator`, parser, money tests unmodified.

## 10. Invariants upheld (CLAUDE.md)

Money stays paise-`Long` (projections divide at display only). `game_event` stays append-only, emitted atomically with LOGGED transitions. Never punish the log: all new pressure (crossing events, weeds) keys off pre-payment choices or explicit regret, never off logging itself; necessities remain exempt. Gate remains offline/local (quip cache; no LLM in path). Budget FK lands with a deliberate `ON DELETE`; schema history `2.json` committed.

## 11. Open questions

None blocking. Cosmetic naming (app name, roaster species) unchanged from parent spec §14.
