# Collection Album Locked Rows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the album's run of identical `• ???` rows with a per-tier count, so every line on the card carries information.

**Architecture:** Extract the counting and its two strings into a pure `CollectionSummary` object with no Compose imports, so the logic is JVM-testable; the Composable becomes a thin renderer over it.

**Tech Stack:** Kotlin, Jetpack Compose Material 3. No new dependencies.

---

## Why

`GreenhouseScreen.kt:154` renders `"• ???"` for every unearned species. The catalogue is 8
uncommon + 6 rare + 2 landmark = 16, so a fresh install that expands the card sees:

```
Uncommon
• ???      x8          <- eight identical rows
a 7-day streak, 3 gate dodges, a no-spend week, or redeeming a regret
Rare
• ???      x6          <- six identical rows
close a month under budget, a 30-day streak, or spend across 8 categories
Landmark
• ???      x2
keep tracking — 6 months, then 12
```

22 lines, of which 16 are identical to each other and carry exactly one bit between them: a
count. The header already shows that count as `2 of 16`.

The card's promise is its own collapse label — *"tap to see what else grows here"* — and the
thing that actually keeps that promise is the condition line under each tier, which says how to
unlock it. The `???` rows say nothing the condition line does not say better.

After: 9 lines, every one informative.

```
Uncommon — 0 of 8
8 still hidden
a 7-day streak, 3 gate dodges, a no-spend week, or redeeming a regret
```

**Deliberately not doing:** revealing anything about a locked species — not its name, not its
archetype, not a silhouette. The existing KDoc on `CollectionCard` is explicit that the point is
to let a user *learn a lotus exists* without being told how to buy one, and half-revealing a
species is a spoiler with extra steps. The count is strictly less information than today's rows
imply, not more.

**Copy note:** `"N still hidden"` was chosen over `"N more grow here"` because the latter reads
wrong at N = total ("8 more" when none are found). `still hidden` needs no singular/plural
branch on the noun. It is one string in one place and easy to change.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/expensegarden/app/ui/CollectionSummary.kt` | **Create.** Pure counting + the two strings. No Compose imports, so JVM tests can load it. Lives in `ui/` because the repo deliberately keeps presentation strings out of `RareCatalog` (`GreenhouseScreen.kt:171`). |
| `app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt` | **Modify.** Lines 139-165: header gains a count, the per-species loop drops locked rows, one summary line replaces them. |
| `app/src/test/java/com/expensegarden/app/ui/CollectionSummaryTest.kt` | **Create.** JVM tests. |

---

### Task 1: The pure summary

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/CollectionSummary.kt`
- Test: `app/src/test/java/com/expensegarden/app/ui/CollectionSummaryTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/ui/CollectionSummaryTest.kt`:

```kotlin
package com.expensegarden.app.ui

import com.expensegarden.app.game.RareTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionSummaryTest {

    @Test fun `a tier heading carries its own progress`() {
        assertEquals("Uncommon — 2 of 8", CollectionSummary.heading(RareTier.UNCOMMON, 2, 8))
    }

    @Test fun `every tier name is title cased`() {
        assertEquals("Landmark — 0 of 2", CollectionSummary.heading(RareTier.LANDMARK, 0, 2))
        assertEquals("Rare — 6 of 6", CollectionSummary.heading(RareTier.RARE, 6, 6))
    }

    @Test fun `the hidden line counts what is left`() {
        assertEquals("6 still hidden", CollectionSummary.hiddenLine(2, 8))
    }

    @Test fun `a fully collected tier has no hidden line`() {
        // The whole point of the change: no row that says nothing.
        assertNull(CollectionSummary.hiddenLine(8, 8))
    }

    @Test fun `an untouched tier reports its whole pool`() {
        assertEquals("8 still hidden", CollectionSummary.hiddenLine(0, 8))
    }

    @Test fun `one remaining reads naturally`() {
        assertEquals("1 still hidden", CollectionSummary.hiddenLine(7, 8))
    }

    @Test fun `more found than the pool holds never goes negative`() {
        // Guards a retired species: foundBy is keyed by id and outlives the catalogue, so a
        // removed entry would leave found > total and print "-1 still hidden".
        assertNull(CollectionSummary.hiddenLine(9, 8))
        assertEquals("Rare — 9 of 8", CollectionSummary.heading(RareTier.RARE, 9, 8))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*CollectionSummaryTest*'
```

Expected: FAIL to compile — `Unresolved reference: CollectionSummary`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/expensegarden/app/ui/CollectionSummary.kt`:

```kotlin
package com.expensegarden.app.ui

import com.expensegarden.app.game.RareTier

/** The album's locked-slot arithmetic and its two strings.
 *
 *  Separate from the Composable so it can be unit-tested, and free of Compose imports so the
 *  JVM suite can load it. Lives in `ui/` rather than beside `RareCatalog` for the same reason
 *  `conditionFor` and `howEarned` do: the catalogue stays free of presentation strings.
 *
 *  This replaced one `"• ???"` row per unearned species. Sixteen identical rows on a fresh
 *  install carried a single count between them, and the card header already showed that count. */
object CollectionSummary {

    fun heading(tier: RareTier, found: Int, total: Int): String =
        "${tier.name.lowercase().replaceFirstChar { it.uppercase() }} — $found of $total"

    /** Null when there is nothing left to say — a fully collected tier gets no row at all,
     *  which is the entire point of the change.
     *
     *  Clamped at zero: `foundBy` is keyed by species id and outlives the catalogue, so
     *  retiring a species would otherwise print "-1 still hidden". */
    fun hiddenLine(found: Int, total: Int): String? {
        val hidden = (total - found).coerceAtLeast(0)
        return if (hidden == 0) null else "$hidden still hidden"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*CollectionSummaryTest*'
```

Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/CollectionSummary.kt app/src/test/java/com/expensegarden/app/ui/CollectionSummaryTest.kt
git commit -m "feat: count an album tier's hidden species instead of listing them blank"
```

---

### Task 2: Render it

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt:139-165`

- [ ] **Step 1: Replace the tier block**

Replace the whole `for (tier in RareTier.values()) { ... }` body (lines 139-165) with:

```kotlin
                for (tier in RareTier.values()) {
                    val pool = RareCatalog.pool(tier)
                    val found = pool.count { state.foundBy.containsKey(it.id) }
                    Text(
                        CollectionSummary.heading(tier, found, pool.size),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    for (species in pool) {
                        val earnedBy = state.foundBy[species.id] ?: continue
                        // Spec §5 asks for the species AND how it was earned. Showing the
                        // specific trigger beats the tier's generic condition line: it tells
                        // you what YOU did, not what someone could do.
                        Text(
                            "• ${species.displayName} — ${howEarned(earnedBy)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    CollectionSummary.hiddenLine(found, pool.size)?.let { hidden ->
                        Text(
                            "• $hidden",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        conditionFor(tier),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
```

Note the `?: continue` on line 4 of the inner loop — that is what removes the locked rows. The
`color =` on the found row is now unconditional, because the branch that needed
`onSurfaceVariant` was the locked one.

- [ ] **Step 2: Build and lint**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest lintDebug
```

Expected: BUILD SUCCESSFUL, 0 lint errors. Two pre-existing Compose-convention warnings
(`ModifierFactoryExtensionFunction` in `GardenHomeScreen.kt`, `ModifierParameter` in
`GardenCanvas.kt`) are expected and must not be "fixed" — see CLAUDE.md.

- [ ] **Step 3: Verify on device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

Open the greenhouse, tap the Collection card to expand. Expected on a fresh or lightly-collected
install: three tier headings each with `N of M`, any earned species listed under its tier with
how it was earned, one `• N still hidden` line per incomplete tier, and the condition line
unchanged. No `???` anywhere. **If this does not match: stop and report.**

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt
git commit -m "fix: replace the album's blank rows with a per-tier hidden count"
```
