# Review Fixes (2026-10-11) Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans — Rajdweep's standing
> rule is that a sequential plan runs inline. Steps use checkbox (`- [ ]`) syntax for tracking.
> If a step's output doesn't match its Expected line: stop and report. Don't improvise.

**Goal:** Fix the four defects the 2026-10-11 review measured: a mistyped budget silently deletes
the budget; dialogs, cards and sheets render Material's baseline purple; the home chrome is
unreadable in dark mode and on overcast skies; and a partly observed first month makes
`RecurringPayees` treat one-off payees as fixed costs, loosening the gate.

**Architecture:** Each fix moves a rule out of convention and into something the compiler or a
test enforces. Save gets a non-null API, so it cannot express "clear". Every `ColorScheme` role
gets a token, and a reflection test fails on any role Material adds. Chrome over the canvas gets a
backdrop whose contrast is tested against every weather's sky. `RecurringPayees` takes the same
"ledger began at" instant `StreakBaseline` uses, as a required parameter.

**Tech Stack:** Kotlin 2.0.20, Jetpack Compose (BOM 2024.09.03 / Material 3 1.3), Room, JUnit 4
(JVM and instrumented). No new dependencies.

---

## Why this plan exists

The review ran 2026-10-11 at `1611107`, while every suite was green (347 JVM, 71 instrumented, 8
Go). Every defect below was measured, not read. The requirements are the findings; there is no
separate spec.

| Finding | Defect | Evidence | Task |
|---|---|---|---|
| 1 | Save on an unparseable budget amount deletes the budget and queues a sync tombstone. No budget means no gate. | Emulator: "10,000" → Save → no budget. DB: 0 budget rows, tombstone `*\|2026-10`. | 1 |
| 4 | Eleven `ColorScheme` roles are never set, so every Card, AlertDialog, DatePicker and sheet is Material's baseline purple. | Measured `#ECE6F0`, `#E6E0E9`, `#F7F2FA` (light), `#2B2930`, `#36343B` (dark) — the baseline values exactly. | 2 |
| 2 | The home chrome sits on an always-light canvas but follows the system theme. | Strip text 16.29:1 → **1.21:1** in dark mode. Links 4.17:1 → **1.41:1**, and **2.38:1** on an overcast sky even in light mode. | 3 |
| 3 | A month with any data counts as a look-back month, so a payee paid once in a partial first month becomes a "fixed cost" next month and passes through the pace line. | Throwaway JVM probe on the real classes: PACE_WARNING → OK. | 4 |

**Order.** Tasks 1–3 before the phone run (by 2026-10-26). Task 3 matters there because half the
run's Expected lines are read off the strip. Task 4 before 2026-11-01, his first full month. Each
task commits on its own; Task 5 verifies all four on the emulator.

## Rulings

Decisions this plan makes — what, why, and the cost if wrong.

1. **Save's fix lives in the ViewModel, not the repository.** `BudgetRepository.setBudget(…, null)`
   meaning "clear" is the 2A sync protocol (spec §2.5), and `BudgetRepositoryTest` pins it. The
   defect is where typed text became an intent. `setBudget(…, Long)` plus `clearBudget(…)` make
   "Save clears" unrepresentable. *If wrong:* a second UI caller of the repository could reintroduce
   it. There is one caller today.
2. **Typed amounts accept grouping commas, whitespace and a leading ₹**, through a new
   `Money.parseUserAmount`. `parseToPaise` stays strict because it also reads scanned QR `am` fields,
   where a comma is a malformed payment. A comma is never a decimal separator in INR, so stripping
   it cannot change a value. *If wrong:* none found.
3. **All three human parse sites switch together:** the budget dialog and both `EntryScreen` sites.
   `EntryScreen` parses twice, on the button and again before showing the gate. If the two
   disagreed, "1,200" would pass the first and silently skip the gate.
4. **The budget field opens focused with its amount selected.** The plain-`String` field put the
   cursor at the start: the review typed "12,000" over a budget and got "12,00010000.00".
   Selecting the text means typing replaces it. It also shows whole rupees (`10000`), not the
   payment format `10000.00`.
5. **Every `ColorScheme` role gets a token, and a reflection test demands one for any role Material
   adds.** Values keep each surface's own Lab hue and step lightness to Material 3's tone targets
   (light containers L\* 96/94/92/90, dim 87; dark lowest 4, then 10/12/17/22, bright 24). One
   deliberate deviation: dark `inverseSurface` is L\* 95, not 90, so `inversePrimary` (the light
   primary) clears 4.5:1 — 4.58 against 4.03. *If wrong:* a colour Rajdweep dislikes; every value
   is one token.
6. **Home renders under the light scheme.** Its canvas never goes dark — `GardenHomeScreen` already
   pins dark status-bar icons for that reason — so the chrome drawn on it shouldn't either. In
   dark mode, home will look like light mode. That is the point.
7. **The theme wrap alone does not fix the strip.** The strip's white matches no `ColorScheme`
   role, so `Surface`'s `contentColorFor()` falls back to `LocalContentColor`. That value comes from
   the app's root `Surface`, which stays dark-themed. Both chrome surfaces set `contentColor`
   explicitly, and Task 5 measures it.
8. **The links get the strip's 82% white backdrop** rather than a new colour. Worst case is
   4.58:1, on the overcast sky's top stop. The margin is thin but over 4.5, and a JVM test over
   every stop of every weather's sky guards it.
9. **`RecurringPayees.detect` takes `ledgerBeganAt` as a required parameter.** Four production
   callers compute fixed spend (gate, strip, dashboard, garden weather), and a default would let one
   skip the floor silently. `foldAllTime` takes it defaulted, matching its `firstObservedDay` and
   sparing five test call sites. Its one production caller sets it in Task 4 Step 6.
10. **A look-back month counts only if the ledger began on or before its 1st**, at day granularity
    like `StreakBaseline`. `StreakBaseline.ledgerBeganAt` names the "earlier of first run and
    oldest event" rule so both share one definition. null means unknown, which keeps today's
    behaviour.
11. **The phone E2E script needs no edits.** No label it quotes changes, and Task 5 Step 9 checks.
12. **Out of scope** (same review, deferred): the entry draft lost on process death; the dashboard
    saying "on pace" with no budget; the OCT signpost framing; `prepareGateView` reading
    `isNecessity` from a StateFlow; tests for `httpapi` auth; scanner orientation; Bharat QR; the
    UPI intent dropping merchant fields.

## Conventions for every command

- Every Gradle command needs `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Anything that touches a device pins `ANDROID_SERIAL=emulator-5554`. The phone must not be
  attached during instrumented runs (CLAUDE.md).
- `adb` lives at `~/Library/Android/sdk/platform-tools/adb`.
- Stage named paths only. Plain commit messages, no attribution lines. Never push unless asked.
- Count what ran, not the exit code. A run of zero tests also says BUILD SUCCESSFUL. Define this
  once per shell; pass a class name to count one class, or `""` for the whole suite:

```bash
testcount() { python3 -c "import glob,sys,xml.etree.ElementTree as E; rs=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml') if sys.argv[1] in f]; print(sum(int(r.get('tests')) for r in rs), 'tests,', sum(int(r.get('failures'))+int(r.get('errors')) for r in rs), 'failed')" "$1"; }
```

## File map

| Task | Create | Modify | Test |
|---|---|---|---|
| 1 | — | `core/Money.kt`, `ui/DashboardViewModel.kt`, `ui/DashboardScreen.kt`, `ui/EntryScreen.kt` | `core/MoneyTest.kt` |
| 2 | `test/…/ui/theme/GardenSchemeTest.kt` | `ui/theme/GardenTokens.kt`, `ui/theme/GardenTheme.kt` | `ui/theme/GardenTokensTest.kt` |
| 3 | — | `ui/theme/ContrastMath.kt`, `ui/theme/GardenTokens.kt`, `ui/GardenHomeScreen.kt`, `MainActivity.kt` | `ui/theme/GardenTokensTest.kt` |
| 4 | — | `game/StreakBaseline.kt`, `stats/RecurringPayees.kt`, `game/GardenFolder.kt`, `data/GardenRepository.kt`, `data/LedgerRepository.kt`, `ui/MainViewModel.kt`, `ui/DashboardViewModel.kt` | `game/StreakBaselineTest.kt`, `stats/RecurringPayeesTest.kt`, `game/GardenFolderTest.kt`, `androidTest/…/data/GateScopeTest.kt` |
| 5 | — | `CLAUDE.md` (test counts) | full suites + emulator |

Paths are under `app/src/main/java/com/expensegarden/app/` unless they start with `test/` or
`androidTest/`.

---

## Task 1: Save can never clear a budget

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/core/Money.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt:62-69`
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt:144-164` and imports
- Modify: `app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt:235,260`
- Test: `app/src/test/java/com/expensegarden/app/core/MoneyTest.kt`

- [ ] **Step 1: Write the failing tests.** Append inside `class MoneyTest`, before its closing brace:

```kotlin
    // ---------- typed amounts (review fix, 2026-10-11) ----------

    @Test fun `typed amounts may carry grouping, spaces and a rupee sign`() {
        assertEquals(1_000_000L, Money.parseUserAmount("10,000"))
        assertEquals(10_000_000L, Money.parseUserAmount("1,00,000"))
        assertEquals(1_000_000L, Money.parseUserAmount("₹10,000"))
        assertEquals(1_000_000L, Money.parseUserAmount(" 10 000 "))
        assertEquals(1_200_050L, Money.parseUserAmount("12,000.50"))
    }

    @Test fun `typed amounts are still strict once the decoration is gone`() {
        assertNull(Money.parseUserAmount(""))
        assertNull(Money.parseUserAmount("abc"))
        assertNull(Money.parseUserAmount("0"))
        assertNull(Money.parseUserAmount("-5"))
        assertNull(Money.parseUserAmount("1,000.505"))
        // The input that deleted a budget on 2026-10-11. It must be a value, never "nothing".
        assertEquals(1_200_000L, Money.parseUserAmount("12,000"))
    }

    @Test fun `an amount field shows whole rupees without paise`() {
        assertEquals("10000", Money.inputText(1_000_000L))
        assertEquals("12000.50", Money.inputText(1_200_050L))
        assertEquals("12000.05", Money.inputText(1_200_005L))
    }
```

- [ ] **Step 2: Run them and watch them fail.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.core.MoneyTest'
```

Expected: `BUILD FAILED`, with `Unresolved reference 'parseUserAmount'` and
`Unresolved reference 'inputText'`.

- [ ] **Step 3: Implement.** In `Money.kt`, insert directly after `parseToPaise` (after its closing
  brace, before the `display` KDoc):

```kotlin
    /** Amounts a person typed. Strips what people actually type — Indian digit grouping
     *  ("1,00,000"), spaces, a leading ₹ — then applies exactly the rules of [parseToPaise].
     *
     *  Kept apart from [parseToPaise] on purpose: that one also reads the `am` field of scanned QR
     *  codes, where a comma is a malformed payment rather than a habit. A comma is never a decimal
     *  separator in INR, so stripping it here cannot change what a value means. */
    fun parseUserAmount(input: String): Long? =
        parseToPaise(input.filterNot { it == ',' || it.isWhitespace() }.removePrefix("₹"))
```

  And insert at the end of the object, after `intentAmount`:

```kotlin

    /** What an amount field shows when editing a value: whole rupees when there are no paise.
     *  [intentAmount]'s "10000.00" is a payment format, not something to put under a cursor. */
    fun inputText(paise: Long): String =
        if (paise % 100 == 0L) (paise / 100).toString() else intentAmount(paise)
```

- [ ] **Step 4: Run them and watch them pass.** Re-run the Step 2 command, then `testcount MoneyTest`.

Expected: `BUILD SUCCESSFUL`, and `18 tests, 0 failed`.

- [ ] **Step 5: Make Save unable to clear.** In `DashboardViewModel.kt`, replace lines 62-69 (the
  KDoc line `/** amountPaise null = clear. …` through the closing brace of `setBudget`) with:

```kotlin
    /** categoryId null = overall. Takes a real amount, never null. Save used to pass its parse
     *  result straight through, and null means CLEAR, so a typo deleted the budget and switched
     *  the gate off (review, 2026-10-11). Clearing is its own call, from its own button. */
    fun setBudget(categoryId: Long?, amountPaise: Long) {
        viewModelScope.launch {
            // Month key resolved at call time, not VM birth. The delete+insert idiom and the
            // sync tombstone rule both live in BudgetRepository now (spec §2.5).
            container.budgets.setBudget(categoryId, ledger.currentMonthKey(), amountPaise)
        }
    }

    fun clearBudget(categoryId: Long?) {
        viewModelScope.launch {
            container.budgets.setBudget(categoryId, ledger.currentMonthKey(), null)
        }
    }
```

- [ ] **Step 6: Rewrite the budget dialog.** In `DashboardScreen.kt`, add these imports beside the
  existing ones:

```kotlin
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
```

  Then replace the whole `target?.let { t -> … }` block (lines 144-164, from `target?.let { t ->`
  through the `}` that closes it, just before `regretTarget?.let`) with:

```kotlin
    target?.let { t ->
        val initial = t.currentPaise?.let { Money.inputText(it) } ?: ""
        // Opens focused with the amount selected, so typing replaces it. The plain-String field
        // put the cursor at the START: typing "12,000" over a budget gave "12,00010000.00".
        var field by remember(t) { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
        var invalid by remember(t) { mutableStateOf(false) }
        val focus = remember(t) { FocusRequester() }
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(if (t.categoryId == null) "Overall budget" else "${t.name} budget") },
            text = {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it; invalid = false },
                    label = { Text("Amount (₹)") },
                    isError = invalid,
                    supportingText = if (invalid) ({ Text("Enter an amount") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                )
                LaunchedEffect(t) { focus.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Save never clears. It used to hand the parse result straight to setBudget,
                    // where null means CLEAR: "10,000" deleted the budget, and the gate went silent
                    // with it (review, 2026-10-11). Clearing has its own button.
                    when (val paise = Money.parseUserAmount(field.text)) {
                        null -> invalid = true
                        else -> { vm.setBudget(t.categoryId, paise); target = null }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (t.currentPaise != null) {
                        TextButton(onClick = { vm.clearBudget(t.categoryId); target = null }) { Text("Clear") }
                    }
                    TextButton(onClick = { target = null }) { Text("Cancel") }
                }
            },
        )
    }
```

- [ ] **Step 7: Switch the entry screen's two parse sites together** (Ruling 3). In `EntryScreen.kt`:

  Line 235 becomes:

```kotlin
                val amountPaise = Money.parseUserAmount(draft.amountText)
```

  Line 260 becomes:

```kotlin
        val amountPaise = Money.parseUserAmount(draft.amountText) ?: return@let
```

  Then confirm no human-input site still uses the strict parser:

```bash
grep -rn "parseToPaise(" app/src/main --include='*.kt'
```

  Expected: exactly three lines — two in `core/Money.kt` (the definition, and the call inside
  `parseUserAmount`) and `capture/UpiUriParser.kt:31`, the scanned-QR path that must stay strict.

- [ ] **Step 8: Run the whole JVM suite.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest
```

  Then `testcount ""`. Expected: `BUILD SUCCESSFUL`, and `350 tests, 0 failed`.

- [ ] **Step 9: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/core/Money.kt app/src/test/java/com/expensegarden/app/core/MoneyTest.kt app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt app/src/main/java/com/expensegarden/app/ui/EntryScreen.kt
git commit -m "fix: never clear a budget from Save

Save passed its parse result straight to setBudget, where null means clear,
so a typo or a comma-grouped amount deleted the budget and turned the gate
off. Save now takes a real amount and shows an error otherwise; Clear has
its own call. Typed amounts accept grouping commas and a rupee sign."
```

---

## Task 2: Every colour role comes from the garden

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/theme/GardenTokens.kt`
- Modify: `app/src/main/java/com/expensegarden/app/ui/theme/GardenTheme.kt:45-110`
- Create: `app/src/test/java/com/expensegarden/app/ui/theme/GardenSchemeTest.kt`
- Test: `app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt`

- [ ] **Step 1: Add the missing tokens and the role maps.** In `GardenTokens.kt`, insert before the
  object's final closing brace (after `val darkStatus = …`):

```kotlin

    // ---- every ColorScheme role, explicitly (review fix, 2026-10-11) -------------------------
    // Material 3 fills any role you don't pass with its baseline PURPLE. The surface-container
    // roles arrived in M3 1.2, and dialogs, sheets and cards moved onto them, so a scheme that set
    // only `surface` still painted all of those lavender (#ECE6F0, #E6E0E9, #F7F2FA). Each value
    // below keeps its surface's Lab hue and steps lightness to M3's tone targets.
    const val LightSurfaceDim = 0xDCDAD4              // L* 87
    const val LightSurfaceBright = LightSurface       // M3: bright is the surface in light
    const val LightSurfaceContainerLowest = 0xFFFFFF  // L* 100
    const val LightSurfaceContainerLow = 0xF5F3ED     // L* 96 — ModalBottomSheet
    const val LightSurfaceContainer = 0xF0EEE8        // L* 94
    const val LightSurfaceContainerHigh = 0xEAE8E2    // L* 92 — AlertDialog
    const val LightSurfaceContainerHighest = 0xE4E2DC // L* 90 — Card
    const val LightOutlineVariant = 0xCCC6B5          // outline's hue at L* 80
    const val LightInverseSurface = 0x32302C          // L* 20
    const val LightInverseOnSurface = 0xF3F1EB        // L* 95
    const val LightInversePrimary = DarkPrimary
    const val LightScrim = 0x000000

    const val DarkSurfaceDim = DarkSurface            // M3: dim is the surface in dark
    const val DarkSurfaceBright = 0x36393D            // L* 24
    const val DarkSurfaceContainerLowest = 0x0A0F13   // L* 4
    const val DarkSurfaceContainerLow = 0x191C1F      // L* 10 — ModalBottomSheet
    const val DarkSurfaceContainer = 0x1D2023         // L* 12
    const val DarkSurfaceContainerHigh = 0x272A2E     // L* 17 — AlertDialog
    const val DarkSurfaceContainerHighest = 0x323538  // L* 22 — Card
    const val DarkOutlineVariant = 0x4A4739           // outline's hue at L* 30
    // L* 95, not M3's 90: lifted so inversePrimary (the light primary) clears 4.5:1 — 4.58 here,
    // 4.03 at 90.
    const val DarkInverseSurface = 0xEDF1F6
    const val DarkInverseOnSurface = 0x2E3134         // L* 20
    const val DarkInversePrimary = LightPrimary
    const val DarkScrim = 0x000000

    /** Role name -> token, for every ColorScheme role. GardenSchemeTest checks both schemes carry
     *  exactly these, and fails on any role Material adds that has no entry here. */
    val lightRoles: Map<String, Int> = mapOf(
        "primary" to LightPrimary, "onPrimary" to LightOnPrimary,
        "primaryContainer" to LightPrimaryContainer, "onPrimaryContainer" to LightOnPrimaryContainer,
        "inversePrimary" to LightInversePrimary,
        "secondary" to LightSecondary, "onSecondary" to LightOnSecondary,
        "secondaryContainer" to LightSecondaryContainer, "onSecondaryContainer" to LightOnSecondaryContainer,
        "tertiary" to LightTertiary, "onTertiary" to LightOnTertiary,
        "tertiaryContainer" to LightTertiaryContainer, "onTertiaryContainer" to LightOnTertiaryContainer,
        "background" to LightSurface, "onBackground" to LightOnSurface,
        "surface" to LightSurface, "onSurface" to LightOnSurface,
        "surfaceVariant" to LightSurfaceVariant, "onSurfaceVariant" to LightOnSurfaceVariant,
        "surfaceTint" to LightPrimary,
        "inverseSurface" to LightInverseSurface, "inverseOnSurface" to LightInverseOnSurface,
        "error" to LightError, "onError" to LightOnError,
        "errorContainer" to LightErrorContainer, "onErrorContainer" to LightOnErrorContainer,
        "outline" to LightOutline, "outlineVariant" to LightOutlineVariant, "scrim" to LightScrim,
        "surfaceBright" to LightSurfaceBright, "surfaceDim" to LightSurfaceDim,
        "surfaceContainer" to LightSurfaceContainer,
        "surfaceContainerHigh" to LightSurfaceContainerHigh,
        "surfaceContainerHighest" to LightSurfaceContainerHighest,
        "surfaceContainerLow" to LightSurfaceContainerLow,
        "surfaceContainerLowest" to LightSurfaceContainerLowest,
    )

    val darkRoles: Map<String, Int> = mapOf(
        "primary" to DarkPrimary, "onPrimary" to DarkOnPrimary,
        "primaryContainer" to DarkPrimaryContainer, "onPrimaryContainer" to DarkOnPrimaryContainer,
        "inversePrimary" to DarkInversePrimary,
        "secondary" to DarkSecondary, "onSecondary" to DarkOnSecondary,
        "secondaryContainer" to DarkSecondaryContainer, "onSecondaryContainer" to DarkOnSecondaryContainer,
        "tertiary" to DarkTertiary, "onTertiary" to DarkOnTertiary,
        "tertiaryContainer" to DarkTertiaryContainer, "onTertiaryContainer" to DarkOnTertiaryContainer,
        "background" to DarkSurface, "onBackground" to DarkOnSurface,
        "surface" to DarkSurface, "onSurface" to DarkOnSurface,
        "surfaceVariant" to DarkSurfaceVariant, "onSurfaceVariant" to DarkOnSurfaceVariant,
        "surfaceTint" to DarkPrimary,
        "inverseSurface" to DarkInverseSurface, "inverseOnSurface" to DarkInverseOnSurface,
        "error" to DarkError, "onError" to DarkOnError,
        "errorContainer" to DarkErrorContainer, "onErrorContainer" to DarkOnErrorContainer,
        "outline" to DarkOutline, "outlineVariant" to DarkOutlineVariant, "scrim" to DarkScrim,
        "surfaceBright" to DarkSurfaceBright, "surfaceDim" to DarkSurfaceDim,
        "surfaceContainer" to DarkSurfaceContainer,
        "surfaceContainerHigh" to DarkSurfaceContainerHigh,
        "surfaceContainerHighest" to DarkSurfaceContainerHighest,
        "surfaceContainerLow" to DarkSurfaceContainerLow,
        "surfaceContainerLowest" to DarkSurfaceContainerLowest,
    )
```

- [ ] **Step 2: Expose the schemes to tests, without wiring the new roles yet.** In `GardenTheme.kt`,
  rename `private val LightScheme` to `internal val GardenLightScheme` (line 45) and
  `private val DarkScheme` to `internal val GardenDarkScheme` (line 71). Line 109 becomes:

```kotlin
        MaterialTheme(colorScheme = if (dark) GardenDarkScheme else GardenLightScheme, content = content)
```

- [ ] **Step 3: Write the tests.** Create `GardenSchemeTest.kt`:

```kotlin
package com.expensegarden.app.ui.theme

import androidx.compose.material3.ColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring, which GardenTokensTest cannot see. That test polices the tokens, and the purple
 * dialogs of 2026-10-11 had nothing wrong with any token: eleven roles were simply never passed,
 * so Material filled them with its baseline.
 *
 * Roles are found by reflection rather than listed, so a Material upgrade that adds one fails
 * here ("no garden token for …") instead of shipping its default silently.
 */
class GardenSchemeTest {

    /** Every Color getter on ColorScheme. Color is an inline class over Long, so each getter
     *  returns a primitive long under a mangled name such as getPrimary-0d7_KjU. */
    private val roles = ColorScheme::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
        .associateBy { it.name.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercase) }

    /** An sRGB Color packs its ARGB into the top 32 bits of the Long. */
    private fun rgbOf(packed: Long): Int = ((packed ushr 32) and 0xFFFFFF).toInt()

    private fun assertWired(label: String, scheme: ColorScheme, tokens: Map<String, Int>) {
        // Without this, a reflection change that finds nothing would pass vacuously.
        assertTrue("reflection found only ${roles.size} colour roles", roles.size >= 36)
        val missing = roles.keys - tokens.keys
        assertTrue("$label: no garden token for $missing, so Material would paint them purple", missing.isEmpty())
        val wrong = roles.mapNotNull { (role, getter) ->
            val want = tokens.getValue(role)
            val got = rgbOf(getter.invoke(scheme) as Long)
            if (want == got) null else "$role: want #%06X, got #%06X".format(want, got)
        }.sorted()
        assertTrue("$label scheme is wired wrong:\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun `every role in both schemes comes from a garden token`() {
        assertWired("light", GardenLightScheme, GardenTokens.lightRoles)
        assertWired("dark", GardenDarkScheme, GardenTokens.darkRoles)
    }
}
```

  And add to `GardenTokensTest`, inside the class:

```kotlin
    private val lightContainers = with(GardenTokens) {
        listOf(LightSurfaceDim, LightSurfaceBright, LightSurfaceContainerLowest, LightSurfaceContainerLow,
            LightSurfaceContainer, LightSurfaceContainerHigh, LightSurfaceContainerHighest)
    }
    private val darkContainers = with(GardenTokens) {
        listOf(DarkSurfaceDim, DarkSurfaceBright, DarkSurfaceContainerLowest, DarkSurfaceContainerLow,
            DarkSurfaceContainer, DarkSurfaceContainerHigh, DarkSurfaceContainerHighest)
    }

    /** Cards, dialogs and sheets sit on these; body and supporting text must clear AA on all. */
    @Test
    fun `text clears 4_5 to 1 on every surface container`() {
        with(GardenTokens) {
            for (c in lightContainers) {
                assertContrast("light onSurface on #%06X".format(c), LightOnSurface, c, 4.5)
                assertContrast("light onSurfaceVariant on #%06X".format(c), LightOnSurfaceVariant, c, 4.5)
            }
            for (c in darkContainers) {
                assertContrast("dark onSurface on #%06X".format(c), DarkOnSurface, c, 4.5)
                assertContrast("dark onSurfaceVariant on #%06X".format(c), DarkOnSurfaceVariant, c, 4.5)
            }
        }
    }

    /** The purple was a hue, not a contrast: #ECE6F0 passes every contrast floor in this file. So
     *  the containers must also stay in their own surface's hue family. */
    @Test
    fun `surface containers keep their surface's hue`() {
        val families = listOf(GardenTokens.LightSurface to lightContainers, GardenTokens.DarkSurface to darkContainers)
        for ((surface, family) in families) {
            // Below chroma 2 a hue angle is rounding noise (white has none), so those are skipped.
            for (c in family.filter { ContrastMath.chroma(it) >= 2.0 }) {
                val sep = ContrastMath.hueSeparation(c, surface)
                assertTrue("#%06X is %.1f deg from its surface, needs 30 or less".format(c, sep), sep <= 30.0)
            }
        }
    }

    @Test
    fun `inverse pairs clear 4_5 to 1`() {
        with(GardenTokens) {
            assertContrast("light inverseOnSurface", LightInverseOnSurface, LightInverseSurface, 4.5)
            assertContrast("light inversePrimary", LightInversePrimary, LightInverseSurface, 4.5)
            assertContrast("dark inverseOnSurface", DarkInverseOnSurface, DarkInverseSurface, 4.5)
            assertContrast("dark inversePrimary", DarkInversePrimary, DarkInverseSurface, 4.5)
        }
    }
```

- [ ] **Step 4: Run them and watch the wiring test fail.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.ui.theme.*'
```

  Expected: `BUILD FAILED`. `GardenSchemeTest` fails with exactly these ten lines. They are the
  Material baseline values measured on screen during the review:

```
light scheme is wired wrong:
inverseOnSurface: want #F3F1EB, got #F5EFF7
inversePrimary: want #9BD374, got #D0BCFF
inverseSurface: want #32302C, got #322F35
outlineVariant: want #CCC6B5, got #CAC4D0
surfaceBright: want #FAF8F2, got #FEF7FF
surfaceContainer: want #F0EEE8, got #F3EDF7
surfaceContainerHigh: want #EAE8E2, got #ECE6F0
surfaceContainerHighest: want #E4E2DC, got #E6E0E9
surfaceContainerLow: want #F5F3ED, got #F7F2FA
surfaceDim: want #DCDAD4, got #DED8E1
```

  (`surfaceContainerLowest` is absent because the baseline is already `#FFFFFF`.) The three new
  `GardenTokensTest` cases pass: their values were checked before this plan was written, and they
  are regression floors rather than drivers.

- [ ] **Step 5: Wire every role.** Replace the whole `GardenLightScheme` and `GardenDarkScheme`
  definitions (lines 45-95) with:

```kotlin
/** All 36 of ColorScheme's roles, each from a token. Leave one out and Material fills it with its
 *  baseline purple. Eleven were left out until 2026-10-11, which is why every dialog, card and
 *  sheet was lavender. GardenSchemeTest fails if this and GardenTokens.lightRoles ever disagree. */
internal val GardenLightScheme = lightColorScheme(
    primary = rgb(GardenTokens.LightPrimary),
    onPrimary = rgb(GardenTokens.LightOnPrimary),
    primaryContainer = rgb(GardenTokens.LightPrimaryContainer),
    onPrimaryContainer = rgb(GardenTokens.LightOnPrimaryContainer),
    inversePrimary = rgb(GardenTokens.LightInversePrimary),
    secondary = rgb(GardenTokens.LightSecondary),
    onSecondary = rgb(GardenTokens.LightOnSecondary),
    secondaryContainer = rgb(GardenTokens.LightSecondaryContainer),
    onSecondaryContainer = rgb(GardenTokens.LightOnSecondaryContainer),
    tertiary = rgb(GardenTokens.LightTertiary),
    onTertiary = rgb(GardenTokens.LightOnTertiary),
    tertiaryContainer = rgb(GardenTokens.LightTertiaryContainer),
    onTertiaryContainer = rgb(GardenTokens.LightOnTertiaryContainer),
    background = rgb(GardenTokens.LightSurface),
    onBackground = rgb(GardenTokens.LightOnSurface),
    surface = rgb(GardenTokens.LightSurface),
    onSurface = rgb(GardenTokens.LightOnSurface),
    surfaceVariant = rgb(GardenTokens.LightSurfaceVariant),
    onSurfaceVariant = rgb(GardenTokens.LightOnSurfaceVariant),
    surfaceTint = rgb(GardenTokens.LightPrimary),
    inverseSurface = rgb(GardenTokens.LightInverseSurface),
    inverseOnSurface = rgb(GardenTokens.LightInverseOnSurface),
    error = rgb(GardenTokens.LightError),
    onError = rgb(GardenTokens.LightOnError),
    errorContainer = rgb(GardenTokens.LightErrorContainer),
    onErrorContainer = rgb(GardenTokens.LightOnErrorContainer),
    outline = rgb(GardenTokens.LightOutline),
    outlineVariant = rgb(GardenTokens.LightOutlineVariant),
    scrim = rgb(GardenTokens.LightScrim),
    surfaceBright = rgb(GardenTokens.LightSurfaceBright),
    surfaceContainer = rgb(GardenTokens.LightSurfaceContainer),
    surfaceContainerHigh = rgb(GardenTokens.LightSurfaceContainerHigh),
    surfaceContainerHighest = rgb(GardenTokens.LightSurfaceContainerHighest),
    surfaceContainerLow = rgb(GardenTokens.LightSurfaceContainerLow),
    surfaceContainerLowest = rgb(GardenTokens.LightSurfaceContainerLowest),
    surfaceDim = rgb(GardenTokens.LightSurfaceDim),
)

internal val GardenDarkScheme = darkColorScheme(
    primary = rgb(GardenTokens.DarkPrimary),
    onPrimary = rgb(GardenTokens.DarkOnPrimary),
    primaryContainer = rgb(GardenTokens.DarkPrimaryContainer),
    onPrimaryContainer = rgb(GardenTokens.DarkOnPrimaryContainer),
    inversePrimary = rgb(GardenTokens.DarkInversePrimary),
    secondary = rgb(GardenTokens.DarkSecondary),
    onSecondary = rgb(GardenTokens.DarkOnSecondary),
    secondaryContainer = rgb(GardenTokens.DarkSecondaryContainer),
    onSecondaryContainer = rgb(GardenTokens.DarkOnSecondaryContainer),
    tertiary = rgb(GardenTokens.DarkTertiary),
    onTertiary = rgb(GardenTokens.DarkOnTertiary),
    tertiaryContainer = rgb(GardenTokens.DarkTertiaryContainer),
    onTertiaryContainer = rgb(GardenTokens.DarkOnTertiaryContainer),
    background = rgb(GardenTokens.DarkSurface),
    onBackground = rgb(GardenTokens.DarkOnSurface),
    surface = rgb(GardenTokens.DarkSurface),
    onSurface = rgb(GardenTokens.DarkOnSurface),
    surfaceVariant = rgb(GardenTokens.DarkSurfaceVariant),
    onSurfaceVariant = rgb(GardenTokens.DarkOnSurfaceVariant),
    surfaceTint = rgb(GardenTokens.DarkPrimary),
    inverseSurface = rgb(GardenTokens.DarkInverseSurface),
    inverseOnSurface = rgb(GardenTokens.DarkInverseOnSurface),
    error = rgb(GardenTokens.DarkError),
    onError = rgb(GardenTokens.DarkOnError),
    errorContainer = rgb(GardenTokens.DarkErrorContainer),
    onErrorContainer = rgb(GardenTokens.DarkOnErrorContainer),
    outline = rgb(GardenTokens.DarkOutline),
    outlineVariant = rgb(GardenTokens.DarkOutlineVariant),
    scrim = rgb(GardenTokens.DarkScrim),
    surfaceBright = rgb(GardenTokens.DarkSurfaceBright),
    surfaceContainer = rgb(GardenTokens.DarkSurfaceContainer),
    surfaceContainerHigh = rgb(GardenTokens.DarkSurfaceContainerHigh),
    surfaceContainerHighest = rgb(GardenTokens.DarkSurfaceContainerHighest),
    surfaceContainerLow = rgb(GardenTokens.DarkSurfaceContainerLow),
    surfaceContainerLowest = rgb(GardenTokens.DarkSurfaceContainerLowest),
    surfaceDim = rgb(GardenTokens.DarkSurfaceDim),
)
```

- [ ] **Step 6: Run them and watch them pass.** Re-run the Step 4 command, then
  `testcount GardenSchemeTest` and `testcount GardenTokensTest`.

  Expected: `BUILD SUCCESSFUL`, `1 tests, 0 failed` and `8 tests, 0 failed`.

- [ ] **Step 7: Run the whole JVM suite.** Use the Task 1 Step 8 command, then `testcount ""`.

  Expected: `354 tests, 0 failed`.

- [ ] **Step 8: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/ui/theme/GardenTokens.kt app/src/main/java/com/expensegarden/app/ui/theme/GardenTheme.kt app/src/test/java/com/expensegarden/app/ui/theme/GardenSchemeTest.kt app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt
git commit -m "fix: give every colour role a garden token

Eleven ColorScheme roles were never set, so Material filled them with its
baseline purple: every card, dialog, date picker and sheet was lavender.
Each role now comes from a token in the surface's own hue, and a reflection
test fails on any role without one."
```

---

## Task 3: The chrome over the canvas follows the canvas

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/theme/ContrastMath.kt` (add `over`)
- Modify: `app/src/main/java/com/expensegarden/app/ui/theme/GardenTokens.kt` (add `CanvasChromeAlpha`)
- Modify: `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt:115,149-155` and imports
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt:94-122`
- Test: `app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt`

- [ ] **Step 1: Write the failing test.** Add these imports to `GardenTokensTest.kt`:

```kotlin
import androidx.compose.ui.graphics.toArgb
import com.expensegarden.app.game.Weather
import com.expensegarden.app.render.GardenPalette
```

  And this test inside the class:

```kotlin
    /** The home chrome sits on the garden's sky, which never goes dark and changes with the
     *  weather. Measured 2026-10-11 with no backdrop: the links were 2.38:1 on an overcast sky in
     *  LIGHT mode and 1.06:1 in dark. So chrome over the canvas uses the light tokens on a white
     *  backdrop, and has to clear AA on every stop of every weather's sky. */
    @Test
    fun `chrome over the canvas clears 4_5 to 1 on every sky`() {
        for (weather in Weather.values()) {
            for (stop in GardenPalette.sky(weather)) {
                val sky = stop.toArgb() and 0xFFFFFF
                val backdrop = ContrastMath.over(0xFFFFFF, GardenTokens.CanvasChromeAlpha, sky)
                assertContrast("ink over $weather #%06X".format(sky), GardenTokens.LightOnSurface, backdrop, 4.5)
                assertContrast("link over $weather #%06X".format(sky), GardenTokens.LightPrimary, backdrop, 4.5)
            }
        }
    }
```

- [ ] **Step 2: Run it and watch it fail.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.ui.theme.GardenTokensTest'
```

  Expected: `BUILD FAILED`, with `Unresolved reference 'over'` and
  `Unresolved reference 'CanvasChromeAlpha'`.

- [ ] **Step 3: Implement.** In `ContrastMath.kt`, insert after `ratio`:

```kotlin

    /** `fg` at `alpha` composited over an opaque `bg`, per channel in sRGB. That is how Android
     *  blends a translucent Surface over whatever is drawn beneath it. */
    fun over(fg: Int, alpha: Float, bg: Int): Int {
        fun mix(shift: Int): Int {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return Math.round(alpha * f + (1 - alpha) * b).coerceIn(0, 255)
        }
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
```

  In `GardenTokens.kt`, insert before the `// ---- budget status` section:

```kotlin
    // ---- chrome over the canvas ------------------------------------------------------------
    /** White at this alpha backs every control drawn over the garden's sky. The sky never goes
     *  dark and changes with the weather, so chrome on it cannot borrow the system theme. Worst
     *  case is the overcast sky's top stop: link 4.58:1, ink 15.20:1. */
    const val CanvasChromeAlpha = 0.82f

```

- [ ] **Step 4: Run it and watch it pass.** Re-run the Step 2 command, then `testcount GardenTokensTest`.

  Expected: `BUILD SUCCESSFUL`, and `9 tests, 0 failed`. This test pins the design (alpha and tokens
  against every sky). It cannot see whether the home screen uses them; Task 5 measures that on
  screen.

- [ ] **Step 5: Give the strip an explicit content colour** (Ruling 7). In `GardenHomeScreen.kt`, add
  `import com.expensegarden.app.ui.theme.GardenTokens` beside the other `com.expensegarden` imports.
  Line 115 currently reads `color = Color.White.copy(alpha = .82f),`. Replace it with:

```kotlin
                color = Color.White.copy(alpha = GardenTokens.CanvasChromeAlpha),
                // Explicit, not inherited: this white matches no ColorScheme role, so Surface's
                // default contentColorFor() falls back to LocalContentColor, the app root's. In
                // dark mode that is light text on white: the 1.21:1 strip of 2026-10-11.
                contentColor = MaterialTheme.colorScheme.onSurface,
```

- [ ] **Step 6: Put the links on the same backdrop.** Replace lines 149-155, the links `Row(…)` from
  `Row(` through its closing `}`, with:

```kotlin
            // The same backdrop as the strip. Bare, these links sat on the sky itself: 2.38:1 on
            // an overcast sky in light mode, 1.41:1 in dark (review, 2026-10-11).
            Surface(
                color = Color.White.copy(alpha = GardenTokens.CanvasChromeAlpha),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenGreenhouse) { Text("🏡 greenhouse") }
                    TextButton(onClick = onOpenSettings) { Text("⚙️ settings") }
                }
            }
```

- [ ] **Step 7: Render home under the light scheme** (Ruling 6). In `MainActivity.kt`, replace
  lines 94-122, from `composable("home") {` through the `}` before `composable("dashboard")`, with:

```kotlin
        composable("home") {
            // The garden canvas never goes dark, so neither does the chrome drawn over it. Under
            // the system theme, dark mode put light text on the strip's white and the links on
            // the sky: 1.21:1 and 1.41:1 (review, 2026-10-11). GardenHomeScreen pins dark
            // status-bar icons for the same reason.
            GardenTheme(dark = false) {
                GardenHomeScreen(
                    gardenVm = gardenVm,
                    vm = vm,
                    aiVm = aiVm,
                    painter = remember {
                        val container = (context.applicationContext as GardenApp).container
                        // No isEmpty() check: asking cost the whole 48 MB decode, on the main thread,
                        // inside composition. SpritePainter already falls back to procedural art per
                        // plant on a miss, so an uninstalled pack renders exactly as it did before.
                        com.expensegarden.app.render.SpritePainter(container.spriteCache.sprites)
                    },
                    spriteCache = (context.applicationContext as GardenApp).container.spriteCache,
                    structures = remember { (context.applicationContext as GardenApp).container.structures },
                    onScan = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan a UPI QR")
                                .setBeepEnabled(false)
                                .setOrientationLocked(true)
                        )
                    },
                    onManual = { vm.startManualDraft(); nav.navigate("entry") },
                    onOpenDashboard = { nav.navigate("dashboard") },
                    onOpenGreenhouse = { nav.navigate("greenhouse") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
        }
```

  `GardenTheme` is already imported in `MainActivity.kt` (line 34).

- [ ] **Step 8: Run the whole JVM suite.** Use the Task 1 Step 8 command, then `testcount ""`.

  Expected: `BUILD SUCCESSFUL`, and `355 tests, 0 failed`.

- [ ] **Step 9: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/ui/theme/ContrastMath.kt app/src/main/java/com/expensegarden/app/ui/theme/GardenTokens.kt app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt app/src/main/java/com/expensegarden/app/MainActivity.kt app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt
git commit -m "fix: keep the home chrome legible over the always-light canvas

The garden sky never goes dark, but the chrome drawn on it followed the
system theme: in dark mode the strip's text was 1.21:1 and the links 1.41:1,
and on an overcast sky the links were 2.38:1 even in light mode. Home now
renders under the light scheme, the strip sets its content colour
explicitly, and the links share the strip's backdrop."
```

---

## Task 4: Only fully observed months can make a payee "fixed"

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/game/StreakBaseline.kt`
- Modify: `app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt:38-66`
- Modify: `app/src/main/java/com/expensegarden/app/game/GardenFolder.kt:97-98,159`
- Modify: `app/src/main/java/com/expensegarden/app/data/GardenRepository.kt:33-39,70-77`
- Modify: `app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt:158-175`
- Modify: `app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt:65-66,78,130,169`
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt:38-40,49`
- Test: `app/src/test/java/com/expensegarden/app/game/StreakBaselineTest.kt`,
  `app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt`,
  `app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt`,
  `app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt`

- [ ] **Step 1: Write the failing test for the shared beginning.** In `StreakBaselineTest.kt`, add
  `import org.junit.Assert.assertNull`, then add inside the class:

```kotlin
    @Test
    fun `the ledger began at the earlier of first run and oldest event`() {
        assertEquals(100L, StreakBaseline.ledgerBeganAt(200L, 100L))
        assertEquals(100L, StreakBaseline.ledgerBeganAt(100L, 200L))
        assertEquals(500L, StreakBaseline.ledgerBeganAt(0L, 500L))    // first run never recorded
        assertEquals(300L, StreakBaseline.ledgerBeganAt(300L, null))  // nothing logged yet
        assertNull(StreakBaseline.ledgerBeganAt(0L, null))
    }
```

- [ ] **Step 2: Run it and watch it fail.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.game.StreakBaselineTest'
```

  Expected: `BUILD FAILED`, with `Unresolved reference 'ledgerBeganAt'`.

- [ ] **Step 3: Implement it, and make `firstObservedDay` use it.** In `StreakBaseline.kt`, insert
  inside the object, before the `firstObservedDay` KDoc:

```kotlin
    /**
     * When the ledger began: the earlier of the device's first run and the oldest event, or null
     * when neither exists. The one definition every "was the ledger watching?" question shares:
     * the streak floor below, and RecurringPayees' look-back months, which had made the same
     * mistake on their own (review, 2026-10-11).
     */
    fun ledgerBeganAt(installedAtMillis: Long, earliestEventMillis: Long?): Long? =
        listOfNotNull(installedAtMillis.takeIf { it > 0L }, earliestEventMillis).minOrNull()

```

  Then in `firstObservedDay`, replace these lines:

```kotlin
        val candidates = listOfNotNull(
            installedAtMillis.takeIf { it > 0L },
            earliestEventMillis,
        )
        // Nothing recorded: treat the month as fully observed rather than inventing a floor that
        // would silently suppress a streak.
        val earliest = candidates.minOrNull() ?: return 1
```

  with:

```kotlin
        // Nothing recorded: treat the month as fully observed rather than inventing a floor that
        // would silently suppress a streak.
        val earliest = ledgerBeganAt(installedAtMillis, earliestEventMillis) ?: return 1
```

- [ ] **Step 4: Run it and watch it pass.** Re-run the Step 2 command, then `testcount StreakBaselineTest`.

  Expected: `BUILD SUCCESSFUL`, and `7 tests, 0 failed`. The six existing tests are what prove the
  refactor kept `firstObservedDay`'s behaviour.

- [ ] **Step 5: Write the failing tests for the floor.** In `RecurringPayeesTest.kt`, replace the
  `detect` helper (lines 26-27) with:

```kotlin
    private fun detect(
        txns: List<TransactionEntity>,
        current: YearMonth = YearMonth.of(2026, 9),
        ledgerBeganAt: Long? = null,
    ) = RecurringPayees.detect(txns, current, zone, ledgerBeganAt)
```

  And add inside the class:

```kotlin
    // ---- a ledger that wasn't watching (review fix, 2026-10-11) ----
    // One dinner in a partly observed October must not make the restaurant "rent" in November.

    private val dinnerInOctober = listOf(txn(7, 2026, 10, 24, amountPaise = 150_000))
    private val november = YearMonth.of(2026, 11)

    @Test fun `a month the ledger only partly watched is not a look-back month`() =
        assertEquals(emptySet<Long>(), detect(dinnerInOctober, november, ledgerBeganAt = at(2026, 10, 20)))

    @Test fun `a ledger that began on the 1st watched the whole month`() =
        assertEquals(setOf(7L), detect(dinnerInOctober, november, ledgerBeganAt = at(2026, 10, 1)))

    @Test fun `a ledger that began earlier counts the month in full`() =
        assertEquals(setOf(7L), detect(dinnerInOctober, november, ledgerBeganAt = at(2026, 9, 28)))

    @Test fun `an unknown beginning keeps every month with data`() =
        assertEquals(setOf(7L), detect(dinnerInOctober, november, ledgerBeganAt = null))
```

  In `GardenFolderTest.kt`, replace the `foldAll` helper (lines 98-105) with:

```kotlin
    private fun foldAll(
        txns: List<TransactionEntity>,
        budgets: List<BudgetEntity> = emptyList(),
        events: List<GameEventEntity> = emptyList(),
        allTimeInvestmentCount: Int = 0,
        today: LocalDate = LocalDate.of(2026, 7, 10),
        houseLevelOverride: Int? = null,
        ledgerBeganAt: Long? = null,
    ) = GardenFolder.foldAllTime(
        txns, categories, budgets, events, allTimeInvestmentCount, today, zone, houseLevelOverride,
        ledgerBeganAt = ledgerBeganAt,
    )
```

  And add inside the class:

```kotlin
    @Test fun `weather does not credit a one-off from a month the ledger only partly watched`() {
        // One dinner on Oct 24 in a ledger that began Oct 20, then ₹4,500 by Nov 5 against ₹20,000.
        // Pace allows ₹3,833 on the 5th, so this is OVERCAST. Counting the dinner as a fixed cost
        // (every txn here shares payee 1) passed the whole month through the pace line: SUNNY.
        val txns = listOf(
            txn(103, day = 24, paise = 150_000, month = 10),
            txn(103, day = 3, paise = 450_000, month = 11),
        )
        val budget = listOf(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 2_000_000, updatedAt = 1L))
        val nov5 = LocalDate.of(2026, 11, 5)
        assertEquals(Weather.OVERCAST, foldAll(txns, budget, today = nov5, ledgerBeganAt = at(20, month = 10)).weather)
        assertEquals(Weather.SUNNY, foldAll(txns, budget, today = nov5).weather)   // unfloored: the defect
    }
```

- [ ] **Step 6: Add the parameter, still unused, and thread it through every caller.** This step
  changes no behaviour. It exists so Step 7 fails on an assertion, not a compile error.

  `RecurringPayees.kt` — replace the `detect` KDoc and signature (lines 38-47) with:

```kotlin
    /**
     * @param txns any superset of the look-back window; non-LOGGED rows are ignored.
     * @param ledgerBeganAt when the ledger began (StreakBaseline.ledgerBeganAt); null = unknown,
     *   which keeps every month with data eligible. Required rather than defaulted: four callers
     *   compute fixed spend, and a default would let one quietly skip the floor.
     * @return payee ids that behave like a fixed monthly cost. Empty when there is no complete
     *   month of history yet, which degrades to the plain linear projection.
     */
    fun detect(
        txns: List<TransactionEntity>,
        currentMonth: YearMonth,
        zone: ZoneId,
        ledgerBeganAt: Long?,
    ): Set<Long> {
```

  `GardenFolder.kt` — after the parameter `firstObservedDay: Int = 1,` (line 98), add:

```kotlin
        /** When the ledger began (StreakBaseline.ledgerBeganAt). null = unknown: every month with
         *  data stays a look-back month for fixed-cost detection. */
        ledgerBeganAt: Long? = null,
```

  and change the line `val recurring = RecurringPayees.detect(ordered, ym, zone)` (originally 159;
  the insertion above moves it) to:

```kotlin
        val recurring = RecurringPayees.detect(ordered, ym, zone, ledgerBeganAt)
```

  `GardenRepository.kt` — after `firstObservedDayOf` (after line 39), add:

```kotlin

    /** When the ledger began. See StreakBaseline.ledgerBeganAt. */
    suspend fun ledgerBeganAt(): Long? =
        StreakBaseline.ledgerBeganAt(prefs.firstObservedAt, db.gameEventDao().earliestCreatedAt())
```

  and in `observeAllTimeGarden`'s `foldAllTime` call, after
  `firstObservedDay = firstObservedDayOf(YearMonth.now(zone)),` add:

```kotlin
                ledgerBeganAt = ledgerBeganAt(),
```

  `LedgerRepository.kt` — `fixedSpentPaise` (line 158) becomes
  `suspend fun fixedSpentPaise(monthKey: String, ledgerBeganAt: Long?): Long {`, and its line 164
  becomes `val recurring = RecurringPayees.detect(window, ym, zone, ledgerBeganAt)`. Then replace
  `evaluateGate` (lines 168-175) with:

```kotlin
    /** Worst severity across scopes, evaluated in the month the txn belongs to (spec §3: backdating).
     *  @param ledgerBeganAt see StreakBaseline.ledgerBeganAt. Required: this repository has no
     *    GardenPrefs of its own to look it up, so every caller has to bring it. */
    suspend fun evaluateGate(categoryId: Long, amountPaise: Long, occurredAt: Long, ledgerBeganAt: Long?): GateVerdict {
        val (day, days) = dayAndLengthOf(occurredAt)
        val monthKey = monthKeyOf(occurredAt)
        return GateAggregator.aggregate(
            scopeInputs(categoryId, occurredAt), amountPaise, fixedSpentPaise(monthKey, ledgerBeganAt), day, days,
        )
    }
```

  `MainViewModel.kt` — in `homeHeader`'s `flow { … }`, after the `lookBackFrom` assignment
  (lines 65-66), add:

```kotlin
            val began = container.garden.ledgerBeganAt()   // once per (re)subscription
```

  The line `val recurring = RecurringPayees.detect(window, ym, zone)` (originally 78) becomes
  `val recurring = RecurringPayees.detect(window, ym, zone, began)`. Both
  `val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt)` lines (originally
  130, in `prepareGateView`, and 169, in `saveManualFromDraft`) become, keeping their indentation:

```kotlin
        val verdict = ledger.evaluateGate(d.categoryId!!, amountPaise, d.occurredAt, container.garden.ledgerBeganAt())
```

  `DashboardViewModel.kt` — in `stats`' `flow { … }`, after the `lookBackFrom` assignment
  (lines 39-40), add:

```kotlin
            val began = container.garden.ledgerBeganAt()   // once per (re)subscription
```

  The line `val recurring = RecurringPayees.detect(window, ym, zone)` (originally 49) becomes
  `val recurring = RecurringPayees.detect(window, ym, zone, began)`.

- [ ] **Step 7: Run the new tests and watch exactly two fail.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.stats.RecurringPayeesTest' --tests 'com.expensegarden.app.game.GardenFolderTest'
```

  Expected: `BUILD FAILED`, with exactly two failing tests:
  - `RecurringPayeesTest > a month the ledger only partly watched is not a look-back month`,
    `expected:<[]> but was:<[7]>`
  - `GardenFolderTest > weather does not credit a one-off from a month the ledger only partly watched`,
    `expected:<OVERCAST> but was:<SUNNY>`

  The other three new `RecurringPayees` tests pass, because they pin behaviour that must not change.

- [ ] **Step 8: Implement the floor.** In `RecurringPayees.kt`, replace the `lookBack` block — the
  five-line comment that begins `// A month counts as a look-back month only if the dataset has
  data in it.` and the three-line `val lookBack = …` statement under it — with:

```kotlin
        // A month counts as a look-back month only if the dataset has data in it AND the ledger
        // watched all of it.
        //
        // The first half is what lets a fresh install with one month of history get detection
        // instead of waiting for a second. It means "absent from a look-back month" only
        // disqualifies a payee when some OTHER payee proves that month existed. The two cases
        // look identical from one payee's rows alone, which is worth knowing before changing this.
        //
        // The second half is the 2026-10-11 fix. A fresh install's partial first month makes every
        // payee seen once look like rent, so next month's spend with them passed straight through
        // the pace line: PACE_WARNING became OK (GateScopeTest pins it). StreakBaseline fixed the
        // same mistake for streaks.
        val lookBack = (1..LOOK_BACK_MONTHS)
            .map { currentMonth.minusMonths(it.toLong()) }
            .filter { byMonth.containsKey(it) && observedInFull(it, ledgerBeganAt, zone) }
```

  And add, inside the object after `detect`:

```kotlin

    /** Day granularity, as in StreakBaseline: a ledger that began on the 1st watched the month. */
    private fun observedInFull(month: YearMonth, ledgerBeganAt: Long?, zone: ZoneId): Boolean =
        ledgerBeganAt == null ||
            !Instant.ofEpochMilli(ledgerBeganAt).atZone(zone).toLocalDate().isAfter(month.atDay(1))
```

- [ ] **Step 9: Run them and watch them pass.** Re-run the Step 7 command, then
  `testcount RecurringPayeesTest` and `testcount GardenFolderTest`.

  Expected: `BUILD SUCCESSFUL`, `14 tests, 0 failed` and `16 tests, 0 failed`.

- [ ] **Step 10: Pin it through the real gate.** In `GateScopeTest.kt`, the three existing
  `repo.evaluateGate(` lines (41, 48, 57) gain `ledgerBeganAt = null`. Edit those three lines only:
  the `Draft(…, occurredAt = nowMillis)` above them is not a gate call, so a blanket replace of
  `occurredAt = nowMillis)` would break it.

```kotlin
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 20_000, occurredAt = nowMillis, ledgerBeganAt = null)
```

```kotlin
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 1_000_000, occurredAt = nowMillis, ledgerBeganAt = null)
```

```kotlin
        val verdict = repo.evaluateGate(categoryId = 103, amountPaise = 5_000, occurredAt = lastMonthMillis, ledgerBeganAt = null)
```

  Then add this test inside the class. The name is snake_case because `androidTest` cannot take
  spaces (CLAUDE.md):

```kotlin
    @Test fun a_payee_seen_once_in_a_partly_watched_month_does_not_loosen_the_next_months_gate() = runBlocking {
        fun noonOn(y: Int, m: Int, d: Int) =
            java.time.LocalDate.of(y, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        db.budgetDao().insert(BudgetEntity(categoryId = null, month = "2026-11", amountPaise = 2_000_000, updatedAt = 1L))  // ₹20,000
        // One dinner on Oct 24 in a ledger that began Oct 20, then the same place again on Nov 3.
        for (day in listOf(noonOn(2026, 10, 24), noonOn(2026, 11, 3))) {
            repo.saveManualLogged(
                LedgerRepository.Draft(vpa = null, payeeName = "Dinner place", amountPaise = 150_000,
                    categoryId = 101, note = null, occurredAt = day),
                breachedAtLogging = false,
            )
        }
        // ₹3,000 more on Nov 5: ₹4,500 against a ₹3,833 pace line is a warning.
        val nov5 = noonOn(2026, 11, 5)
        val floored = repo.evaluateGate(categoryId = 103, amountPaise = 300_000, occurredAt = nov5,
            ledgerBeganAt = noonOn(2026, 10, 20))
        assertEquals(Severity.PACE_WARNING, floored.severity)
        // Unfloored, the dinner counts as rent and the warning goes silent: the 2026-10-11 defect.
        val unfloored = repo.evaluateGate(categoryId = 103, amountPaise = 300_000, occurredAt = nov5,
            ledgerBeganAt = null)
        assertEquals(Severity.OK, unfloored.severity)
    }
```

- [ ] **Step 11: Run the whole JVM suite.** Use the Task 1 Step 8 command, then `testcount ""`.

  Expected: `BUILD SUCCESSFUL`, and `361 tests, 0 failed`.

- [ ] **Step 12: Run `GateScopeTest` on the emulator.** Boot it with the flags from CLAUDE.md, and
  wait until `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell getprop sys.boot_completed`
  prints `1`:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim -gpu host -no-snapshot-save
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.expensegarden.app.data.GateScopeTest
```

  Expected: `Finished 4 tests on Pixel_8_API_35(AVD) - 15` and `BUILD SUCCESSFUL`. This uninstalls
  the app; Task 5 reinstalls it. Leave the emulator running for Task 5.

- [ ] **Step 13: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/game/StreakBaseline.kt app/src/main/java/com/expensegarden/app/stats/RecurringPayees.kt app/src/main/java/com/expensegarden/app/game/GardenFolder.kt app/src/main/java/com/expensegarden/app/data/GardenRepository.kt app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/main/java/com/expensegarden/app/ui/DashboardViewModel.kt app/src/test/java/com/expensegarden/app/game/StreakBaselineTest.kt app/src/test/java/com/expensegarden/app/stats/RecurringPayeesTest.kt app/src/test/java/com/expensegarden/app/game/GardenFolderTest.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "fix: only count fully observed months when spotting fixed costs

A month counted as a look-back month if it held any data, so on a fresh
install every payee paid once in the partial first month looked like rent.
Next month their spend passed straight through the pace line, turning
warnings silent and the garden sunnier. Look-back months now have to be
fully observed, using the same ledger-began-at rule as the streak floor."
```

---

## Task 5: Verify on the emulator, then update the counts

Save Appendix A as `measure.py` and Appendix B as `ux.py` in the session scratchpad. Then define
two shell functions, which zsh needs because it does not word-split variables:

```bash
S=<any scratch directory — in a Claude session, the session scratchpad>; u() { python3 "$S/ux.py" "$@"; }; m() { python3 "$S/measure.py" "$@"; }
```

Coordinates are for the Pixel_8_API_35 emulator (1080×2400) and were measured during the review.
If an element has moved, re-read its `y` from `u texts`.

- [ ] **Step 1: Run the whole JVM suite from scratch** (the cache cannot prove anything about today):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --rerun-tasks
```

  Then `testcount ""`. Expected: `361 tests, 0 failed`.

- [ ] **Step 2: Run the whole instrumented suite.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

```bash
python3 -c "import glob,os,xml.etree.ElementTree as E; f=max(glob.glob('app/build/outputs/androidTest-results/connected/debug/*.xml'),key=os.path.getmtime); r=E.parse(f).getroot(); print(r.get('tests'), 'tests,', r.get('failures'), 'failures,', r.get('errors'), 'errors')"
```

  Expected: `72 tests, 0 failures, 0 errors`.

- [ ] **Step 3: Install and open.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_SERIAL=emulator-5554 ./gradlew installDebug
```

```bash
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am start -W -n com.expensegarden.app/.MainActivity
```

  Expected: `Installed on 1 device.`, then `Status: ok`.

- [ ] **Step 4: Save never clears (Task 1).** Run these in order:

  1. `u tap "set a budget  ›"`, then `u tap "Set overall budget"`.
  2. `u texts | grep EditText`. Expected: one `EditText` line carrying the `F` (focused) flag —
     the field focused itself, with nothing tapped.
  3. `u type "10,000"`, `u tap "Save"`, then `u texts | grep Budget`.
     Expected: `Budget: ₹10,000`. This is the input that deleted the budget during the review.
  4. `u tap "Budget: ₹10,000"`, then `u texts | grep EditText`. Expected: `'10000'`, not
     `'10000.00'`.
  5. `u type "abc"` (it replaces the selected amount), `u tap "Save"`, then
     `u texts | grep -E "Enter an amount|Overall budget"`. Expected: both lines — the dialog stayed
     open and shows the error.
  6. `u tap "Cancel"`, then `u texts | grep Budget`. Expected: `Budget: ₹10,000`, untouched.
  7. Run:

```bash
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell "run-as com.expensegarden.app sqlite3 databases/garden.db \"SELECT 'budgets', COUNT(*) FROM budget; SELECT 'tombstones', COUNT(*) FROM sync_tombstone;\""
```

     Expected: `budgets|1` and `tombstones|0`.

- [ ] **Step 5: A comma in the entry screen.** `u tap "← garden"`, `u tap "Log manually"`,
  `u type "1,200"`, `u tap "Paid to"`, `u type "Kirana"`, `u tap "Groceries"`, `u tap "Log it"`,
  then `u texts | head -1`. Expected: `'₹1,200'`. (The pace label beside it depends on the day of
  the month, so it is not part of this check.)

- [ ] **Step 6: Light-mode colours (Task 2).**
  1. `u tap "₹10,000 ·"` (the strip), run `u shot c1`, then `m $S/c1.png mode 700 430 1020 470`. Expected:
     `#E4E2DC` (the card; it was `#E6E0E9`).
  2. `u tap "Budget: ₹10,000"`, run `u shot c2`, then `m $S/c2.png mode 150 870 930 960`. Expected:
     `#EAE8E2` (the dialog; it was `#ECE6F0`). Then `u tap "Cancel"` and `u tap "← garden"`.
  3. `u tap "Log manually"`, `u tap "All…"`, run `u shot c3`, then
     `m $S/c3.png mode 100 1300 1000 1340`. Expected: `#F5F3ED` (the sheet; it was `#F7F2FA`).
     Then `u back` twice.

- [ ] **Step 7: Light-mode home contrast (Task 3).** Run `u shot h1`, then
  `m $S/h1.png contrast 50 180 260 232` and `m $S/h1.png contrast 125 325 300 360`. Expected: the
  strip is at least 15:1 (it was 16.29) and the links at least 4.5:1, about 5.0 (they were 4.17).

- [ ] **Step 8: Dark mode (Tasks 2 and 3).** Run
  `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell cmd uimode night yes`, wait 3
  seconds, then repeat Step 7's two measurements as `h2`. Expected: the strip is at least 15:1 (it
  was **1.21**) and the links at least 4.5:1 (they were **1.41**). In dark mode, home should look
  exactly like light mode.

  Then `u tap "₹10,000 ·"`, `u shot d1`, `m $S/d1.png mode 700 430 1020 470`. Expected: `#323538` (it
  was `#36343B`). Then `u tap "Budget: ₹10,000"`, `u shot d2`, `m $S/d2.png mode 150 870 930 960`.
  Expected: `#272A2E` (it was `#2B2930`). Finally `u tap "Cancel"` and run
  `~/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell cmd uimode night no`.

- [ ] **Step 9: The phone script's labels still exist** (Ruling 11):

```bash
for s in "set a budget" "This month" "Set overall budget" "Budget: " "Scan & pay" "Log manually" "Continue to pay" "Log it" "Discard" "go through?" "This puts you ahead of pace" "This ends a" "This grows a weed" "Not now" "Pay anyway" "after this" "Nothing grows badly here" "Continue" "Back" "Collection" "waiting — your next purchase will grow one" "All…" "on pace" "ahead of pace" "over budget" "Not a UPI QR" "No UPI app found" "Pay with" "← garden"; do grep -rqF -- "$s" app/src/main || echo "MISSING: $s"; done; echo checked
```

  Expected: only `checked`.

- [ ] **Step 10: Shut the emulator down.**

```bash
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu kill
```

- [ ] **Step 11: Update the test counts, and commit.**

```bash
sed -i '' 's/# JVM unit tests (347)/# JVM unit tests (361)/; s/# instrumented (71)/# instrumented (72)/' CLAUDE.md
```

```bash
grep -n "JVM unit tests\|instrumented (" CLAUDE.md
```

  Expected: lines reading `(361)` and `(72)`.

```bash
git add CLAUDE.md
git commit -m "docs: update test counts after the review fixes"
```

- [ ] **Step 12: Report.** Give the before and after numbers from Steps 6-8, and the commits. Do not
  push unless asked.

---

## Appendix A — `measure.py`

```python
#!/usr/bin/env python3
"""Measure a screenshot (adb exec-out screencap -p) without PIL, which this machine lacks.

  measure.py shot.png contrast x0 y0 x1 y1   darkest pixel vs the most common pixel, as a WCAG ratio
  measure.py shot.png mode x0 y0 x1 y1       the most common colour in the region
"""
import struct, sys, zlib
from collections import Counter

def decode(path, y_max):
    data = open(path, "rb").read(); pos = 8; idat = b""
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]; typ = data[pos + 4:pos + 8]; body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR": w, h, bd, ct = struct.unpack(">IIBB", body[:10])
        if typ == b"IDAT": idat += body
        pos += 12 + ln
    raw = zlib.decompress(idat); bpp = 4 if ct == 6 else 3; stride = w * bpp; prev = bytearray(stride); rows = []
    for r in range(y_max + 1):
        f = raw[r * (stride + 1)]; line = bytearray(raw[r * (stride + 1) + 1:(r + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0; b = prev[i]; c = prev[i - bpp] if i >= bpp else 0
            if f == 1: line[i] = (line[i] + a) & 255
            elif f == 2: line[i] = (line[i] + b) & 255
            elif f == 3: line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return lambda x, y: tuple(rows[y][x * bpp:x * bpp + 3])

def lum(c):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = map(ch, c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b

def ratio(a, b):
    hi, lo = sorted([lum(a), lum(b)], reverse=True)
    return (hi + 0.05) / (lo + 0.05)

path, cmd = sys.argv[1], sys.argv[2]
x0, y0, x1, y1 = map(int, sys.argv[3:7])
px = decode(path, y1)
region = [px(x, y) for y in range(y0, y1) for x in range(x0, x1)]
mode = Counter(region).most_common(1)[0][0]
if cmd == "mode":
    print("#%02X%02X%02X" % mode)
else:
    ink = min(region, key=lum)
    print("ink #%02X%02X%02X on #%02X%02X%02X: %.2f:1" % (*ink, *mode, ratio(ink, mode)))
```

## Appendix B — `ux.py`

```python
#!/usr/bin/env python3
"""Drive the app by visible text. shot NAME | tap TEXT [nth] | texts | type TEXT | key CODE | back"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET
ADB = [os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"), "-s", os.environ.get("SERIAL", "emulator-5554")]
S = os.path.dirname(os.path.abspath(__file__))

def run(*a, out=None):
    return subprocess.run(ADB + list(a), check=True, stdout=out or subprocess.PIPE, stderr=subprocess.PIPE).stdout

def nodes():
    run("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    root = ET.fromstring(run("exec-out", "cat", "/sdcard/ui.xml"))
    for n in root.iter("node"):
        t = n.get("text") or n.get("content-desc") or ""
        focused = n.get("focused") == "true"
        b = re.findall(r"\d+", n.get("bounds", ""))
        if (t or focused) and len(b) == 4:   # a focused, still-empty field has no text
            x1, y1, x2, y2 = map(int, b)
            yield t, (x1 + x2) // 2, (y1 + y2) // 2, n.get("class", ""), focused

cmd = sys.argv[1]
if cmd == "shot":
    with open(f"{S}/{sys.argv[2]}.png", "wb") as f:
        run("exec-out", "screencap", "-p", out=f)
    print(f"{S}/{sys.argv[2]}.png")
elif cmd == "texts":
    for t, x, y, c, f in nodes():
        print(f"{y:5d} {x:5d}  {c.split('.')[-1]:14s} {'F' if f else ' '} {t!r}")
elif cmd == "tap":
    want, nth = sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0
    found = list(nodes())
    hits = [(x, y) for t, x, y, _, _ in found if t == want] or [(x, y) for t, x, y, _, _ in found if want and want in t]
    if len(hits) <= nth:
        sys.exit(f"no node with text {want!r}")
    x, y = hits[nth]
    run("shell", "input", "tap", str(x), str(y))
    print(f"tapped {want!r} at {x},{y}")
elif cmd == "type":
    run("shell", "input", "text", sys.argv[2].replace(" ", "%s"))
    print("typed", sys.argv[2])
elif cmd == "key":
    run("shell", "input", "keyevent", sys.argv[2])
    print("key", sys.argv[2])
elif cmd == "back":
    run("shell", "input", "keyevent", "4")
    print("back")
time.sleep(float(os.environ.get("SETTLE", "1.2")))
```
