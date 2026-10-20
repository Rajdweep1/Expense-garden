# Remaining Fixes (2026-10-18) Implementation Plan

> **For agentic workers:** execute inline with superpowers:executing-plans. Steps use checkbox
> (`- [ ]`) syntax. If a step's output doesn't match its Expected line: stop and report.

**Goal:** Clear everything left on the list that needs neither the phone nor 2B's accounts: the
scanner opens upright, a half-filled entry survives Android killing the app, the dashboard stops
claiming "on pace" with no budget, the gate reads necessity from the database, and month signs
stop hanging off the island's edge.

**Architecture:** Each fix is small, and each gets a test where one can bite. Pure decisions move
into small JVM-testable functions (`paceLine`, `IsoMath.signpostSide`, `DraftState`); the gate's
necessity lookup moves into `LedgerRepository`, tested against the seeded Room DB; the scanner
orientation is configuration, so its proof is on the device. Task 0 captures every defect on the
emulator *before* any fix, so Task 6 can show each one flipping.

**Tech Stack:** Kotlin 2.0.20, Compose, Room, `SavedStateHandle` (lifecycle 2.8.6 — already on
the compile classpath through activity and navigation, so no new dependency), zxing-android-embedded
4.3.0.

---

## Why this plan exists

The 2026-10-11 review's open items, re-read on 2026-10-18 at `fb0422e`:

| # | Defect | Evidence |
|---|---|---|
| 1 | The scanner opens sideways. | zxing's merged-manifest `CaptureActivity` is `sensorLandscape`; the app sets `setOrientationLocked(true)` and never overrides it (`MainActivity.kt`, `ScanOptions`). |
| 2 | A half-filled entry, or a scanned payee, is lost when Android kills the backgrounded app. | `MainViewModel.draft` is a plain `MutableStateFlow` with no `SavedStateHandle` (`MainViewModel.kt:53`). The review's repro also landed on the garden, but it relaunched with a different intent than it launched with, which can start a fresh activity instead of restoring; Task 0 reproduces it properly. |
| 3 | The dashboard says "on pace" with no budget set. | `DashboardScreen.kt:113-117` renders `severityLine(s.overallSeverity)` unconditionally, and severity is `OK` when there is no budget. |
| 4 | The gate decides "is this a necessity?" from a UI `StateFlow`. | `MainViewModel.kt:134` reads `categories.value`, which is `emptyList()` until Room's first emission, and an empty list answers "not a necessity". Latent, never observed. |
| 5 | A month sign on the island's left corner hangs off the screen. | Every sign is drawn left of its tile (`GardenCanvas.kt`, `drawMarkers`: `tileCenterX - tileW * .40f`); home opens at zoom 1.12, which crops exactly that strip. Screenshot `ux05_crop.png` in the review. |

**Still parked, on purpose:** Bharat QR support and the UPI intent's dropped merchant fields.
Only the phone run can say whether either matters.

## Rulings

1. **Scanner: zxing's documented recipe** — `CaptureActivity` overridden to `fullSensor` with
   `tools:replace`, plus `setOrientationLocked(false)` — rather than forcing portrait. It follows
   how the phone is held, which upright is portrait.
2. **The draft is saved one field per key, not as a `Parcelable`.** Every `EntryDraft` field is a
   type a `Bundle` carries, so this needs no `kotlin-parcelize` plugin, and the build stays as
   pinned. Every change to the draft is written through to the `SavedStateHandle`.
3. **Pace line: none at all without a budget**, rather than "no budget". The home strip already
   says "set a budget ›", and the dashboard's own button says "Set overall budget".
4. **Necessity is read from the database by `LedgerRepository.isNecessity`**, the repository that
   already owns the gate's other reads. `MainViewModel.categories` stays, because the "All…" sheet
   uses it.
5. **Signs stand on the house-facing side of their tile on the home island only.** The greenhouse's
   month cards open at zoom 1 and were never clipped, so `worldMode = false` keeps today's
   left-hand placement exactly.
6. **The camera grant in Task 0 uses `pm grant` on the emulator**, the dev device, so the system
   permission dialog doesn't sit over the scanner being measured.

## Conventions

- Every Gradle command needs `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
  Anything on a device pins `ANDROID_SERIAL=emulator-5554`; the phone must not be attached.
- `testcount` (define once per shell):

```bash
testcount() { python3 -c "import glob,sys,xml.etree.ElementTree as E; rs=[E.parse(f).getroot() for f in glob.glob('app/build/test-results/testDebugUnitTest/*.xml') if sys.argv[1] in f]; print(sum(int(r.get('tests')) for r in rs), 'tests,', sum(int(r.get('failures'))+int(r.get('errors')) for r in rs), 'failed')" "$1"; }
```

- Device helpers: Appendix B of `2026-10-11-review-fixes.md` (`ux.py`) saved as `$S/ux.py`, with
  `u() { python3 "$S/ux.py" "$@"; }` and `A=~/Library/Android/sdk/platform-tools/adb`. `$S` is the
  session scratchpad.
- Stage named paths only, plain commit messages, no attribution lines, no push unless asked.

**Done when:** 365 JVM tests (361 + 4) and 73 instrumented (72 + 1) pass, and Task 6 shows each of
the five defects from Task 0 flipped on the emulator.

---

## Task 0: Capture every defect on the current build

- [ ] **Step 1: Boot, install, and launch the way the launcher does.** Boot the emulator with
  CLAUDE.md's flags and wait for `sys.boot_completed` to read `1`. Then:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_SERIAL=emulator-5554 ./gradlew installDebug
```

```bash
$A -s emulator-5554 shell pm clear com.expensegarden.app && $A -s emulator-5554 shell pm grant com.expensegarden.app android.permission.CAMERA && $A -s emulator-5554 shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.expensegarden.app/.MainActivity
```

  Every relaunch in this plan uses that same `am start -a MAIN -c LAUNCHER` intent, so Android
  restores the task rather than starting a fresh one.

- [ ] **Step 2: Defect 3 — "on pace" with no budget.** `u tap "set a budget  ›"`, then
  `u texts | grep -E "on pace|ahead of pace|over budget"`. Expected: `'on pace'`. Then
  `u tap "← garden"`.

- [ ] **Step 3: Defect 5 — the clipped sign.** `u tap "Log manually"`, `u type "450"`,
  `u tap "Paid to"`, `u type "Chaiwala"`, `u tap "Groceries"`, `u tap "Log it"`, wait 2 seconds,
  then `u shot t0_sign` and `sips -c 420 520 --cropOffset 780 0 "$S/t0_sign.png" --out "$S/t0_sign_crop.png"`.
  Expected, viewing the crop: the month sign's plate is cut off at the left edge, as in the
  review's `ux05_crop.png`.

- [ ] **Step 4: Defect 1 — the sideways scanner.** `u tap "Scan & pay"`, wait 2 seconds, then:

```bash
$A -s emulator-5554 exec-out screencap -p > "$S/t0_scan.png" && sips -g pixelWidth -g pixelHeight "$S/t0_scan.png" | tail -2
```

  Expected: `pixelWidth: 2400` and `pixelHeight: 1080`. The display rotated to landscape for the
  scanner. Then `u back`.

- [ ] **Step 5: Defect 2 — the lost draft.** `u tap "Log manually"`, `u type "250"`,
  `u tap "Paid to"`, `u type "Kirana"`, `u tap "Groceries"`, then background and kill it, and
  relaunch with the same intent:

```bash
$A -s emulator-5554 shell input keyevent KEYCODE_HOME && sleep 1 && $A -s emulator-5554 shell am kill com.expensegarden.app && sleep 1 && echo "pid after kill: '$($A -s emulator-5554 shell pidof com.expensegarden.app)'" && $A -s emulator-5554 shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.expensegarden.app/.MainActivity && sleep 3
```

  Then `u texts | head -6`. Expected: `pid after kill: ''`, then the **entry screen** — `'Log an
  expense'` — with the amount and payee fields **empty**. Navigation restored itself; the draft
  didn't. If the garden appears instead, navigation isn't being restored at all: stop and report,
  because this plan's fix for defect 2 would then be incomplete. Then `u tap "← garden"`.

  Defect 4 has no on-device repro; it's a race against Room's first emission.

---

## Task 1: The scanner opens upright

**Files:** Modify `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/expensegarden/app/MainActivity.kt`.

- [ ] **Step 1: Override the scanner's orientation.** In `AndroidManifest.xml`, give the
  `<manifest>` tag the tools namespace:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
```

  and add, inside `<application>` after the `MainActivity` element:

```xml
        <!-- zxing ships CaptureActivity locked to sensorLandscape, so the scanner opened
             sideways at the counter. fullSensor plus setOrientationLocked(false) in MainActivity
             is the library's documented override: it follows how the phone is held. -->
        <activity
            android:name="com.journeyapps.barcodescanner.CaptureActivity"
            android:screenOrientation="fullSensor"
            tools:replace="screenOrientation" />
```

- [ ] **Step 2: Unlock the orientation.** In `MainActivity.kt`, `.setOrientationLocked(true)`
  becomes `.setOrientationLocked(false)`.

- [ ] **Step 3: Build and read the merged manifest.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:processDebugMainManifest -q && grep -A3 'name="com.journeyapps.barcodescanner.CaptureActivity"' app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml | grep -o 'screenOrientation="[a-zA-Z]*"'
```

  Expected: `screenOrientation="fullSensor"` (it was `sensorLandscape`).

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/expensegarden/app/MainActivity.kt
git commit -m "fix: open the scanner upright

zxing ships its scan activity locked to sensorLandscape, so the scanner
opened sideways. The manifest now overrides it to fullSensor, which with
setOrientationLocked(false) is the library's documented way to follow how
the phone is held."
```

---

## Task 2: No pace verdict without a budget

**Files:** Create `app/src/main/java/com/expensegarden/app/ui/DashboardLabels.kt`,
`app/src/test/java/com/expensegarden/app/ui/DashboardLabelsTest.kt`; modify `DashboardScreen.kt`.

- [ ] **Step 1: Write the failing test.** Create `DashboardLabelsTest.kt`:

```kotlin
package com.expensegarden.app.ui

import com.expensegarden.app.gate.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardLabelsTest {
    @Test fun `there is no pace without a budget`() {
        // Severity is OK when there is no budget, which the dashboard used to print as "on pace".
        assertNull(paceLine(Severity.OK, null))
        assertEquals("on pace", paceLine(Severity.OK, 1_000_000L))
        assertEquals("ahead of pace", paceLine(Severity.PACE_WARNING, 1_000_000L))
        assertEquals("over budget", paceLine(Severity.BREACH, 1_000_000L))
    }
}
```

- [ ] **Step 2: Run it and watch it fail.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.ui.DashboardLabelsTest' 2>&1 | grep -oE "Unresolved reference '[a-zA-Z]+'|BUILD [A-Z]+" | sort -u
```

  Expected: `BUILD FAILED` and `Unresolved reference 'paceLine'`.

- [ ] **Step 3: Implement.** Create `DashboardLabels.kt`, in its own file so the test loads
  nothing Compose-related:

```kotlin
package com.expensegarden.app.ui

import com.expensegarden.app.gate.Severity

/** The pace verdict, or nothing when there is no budget to be on pace against. The dashboard used
 *  to say "on pace" with no budget at all (review, 2026-10-11). */
internal fun paceLine(severity: Severity, overallBudgetPaise: Long?): String? =
    if (overallBudgetPaise == null) null else when (severity) {
        Severity.OK -> "on pace"
        Severity.PACE_WARNING -> "ahead of pace"
        Severity.BREACH -> "over budget"
    }
```

  In `DashboardScreen.kt`, replace the pace `Text(…)` block (`severityLine(s.overallSeverity),` and
  the lines around it, `DashboardScreen.kt:113-117`) with:

```kotlin
                    paceLine(s.overallSeverity, s.overallBudgetPaise)?.let {
                        Text(
                            it,
                            color = severityColor(s.overallSeverity),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
```

  and delete the private `severityLine` function (`DashboardScreen.kt:249-253`), its only caller
  now gone.

- [ ] **Step 4: Run it and watch it pass.** Re-run the Step 2 command without the `grep`, then
  `testcount DashboardLabelsTest`. Expected: `BUILD SUCCESSFUL`, `1 tests, 0 failed`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/ui/DashboardLabels.kt app/src/test/java/com/expensegarden/app/ui/DashboardLabelsTest.kt app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt
git commit -m "fix: show no pace verdict without a budget

Severity is OK when there is no budget, and the dashboard printed that as
\"on pace\". With no budget there is nothing to be on pace against, so the
line is now absent; the budget button already says what to do."
```

---

## Task 3: The gate reads necessity from the database

**Files:** Modify `LedgerRepository.kt`, `MainViewModel.kt`; test `GateScopeTest.kt` (instrumented).

- [ ] **Step 1: Write the failing test.** Add to `GateScopeTest`:

```kotlin
    @Test fun the_gate_reads_necessity_from_the_database() = runBlocking {
        // Seed: Groceries (2) and Rent (401) are necessities; Shopping (7) is not.
        assertEquals(true, repo.isNecessity(2))
        assertEquals(true, repo.isNecessity(401))
        assertEquals(false, repo.isNecessity(7))
        assertEquals(false, repo.isNecessity(9_999))
    }
```

- [ ] **Step 2: Watch it fail to compile.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew compileDebugAndroidTestKotlin 2>&1 | grep -oE "Unresolved reference '[a-zA-Z]+'|BUILD [A-Z]+" | sort -u
```

  Expected: `BUILD FAILED` and `Unresolved reference 'isNecessity'`.

- [ ] **Step 3: Implement.** In `LedgerRepository.kt`, insert before `    private suspend fun save(`:

```kotlin
    /** Whether the gate treats [categoryId] as a necessity, read from the database. It used to be
     *  read from a UI StateFlow, which is empty until Room's first emission and would then have
     *  answered "not a necessity": a quip and a dodge credit for a chemist run (review, 2026-10-11). */
    suspend fun isNecessity(categoryId: Long): Boolean =
        db.categoryDao().byId(categoryId)?.isNecessity ?: false

```

  In `MainViewModel.kt`, the line
  `val isNecessity = categories.value.firstOrNull { it.id == d.categoryId }?.isNecessity ?: false`
  becomes `val isNecessity = ledger.isNecessity(d.categoryId!!)`.

- [ ] **Step 4: Run the JVM suite** (the instrumented test runs in Task 6, with the emulator).

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin 2>&1 | grep -E "BUILD|^e: "
```

  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/data/LedgerRepository.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt app/src/androidTest/java/com/expensegarden/app/data/GateScopeTest.kt
git commit -m "fix: read necessity from the database at the gate

The gate decided whether a payment was a necessity from the categories
StateFlow, which is empty until Room's first emission; an empty list answers
\"not a necessity\". LedgerRepository now reads it from the database, next to
the gate's other reads."
```

---

## Task 4: Month signs face the house

**Files:** Modify `render/IsoMath.kt`, `render/GardenCanvas.kt`; test `render/IsoMathTest.kt`.

- [ ] **Step 1: Write the failing test.** Add to `IsoMathTest`:

```kotlin
    @Test fun `a month sign faces the house on the home island and keeps left elsewhere`() {
        assertEquals(1f, IsoMath.signpostSide(tileCenterX = 100f, houseX = 500f, worldMode = true), 0f)
        assertEquals(-1f, IsoMath.signpostSide(tileCenterX = 900f, houseX = 500f, worldMode = true), 0f)
        assertEquals(-1f, IsoMath.signpostSide(tileCenterX = 500f, houseX = 500f, worldMode = true), 0f)
        assertEquals(-1f, IsoMath.signpostSide(tileCenterX = 100f, houseX = 500f, worldMode = false), 0f)
    }
```

- [ ] **Step 2: Watch it fail to compile.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.render.IsoMathTest' 2>&1 | grep -oE "Unresolved reference '[a-zA-Z]+'|BUILD [A-Z]+" | sort -u
```

  Expected: `BUILD FAILED` and `Unresolved reference 'signpostSide'`.

- [ ] **Step 3: Implement.** In `IsoMath.kt`, directly after `    companion object {`, insert:

```kotlin
        /** Which side of its tile a month sign stands on: -1 left (the original placement), +1
         *  right. On the home island a sign stands on the house-facing side, so one on the left
         *  corner no longer hangs past the island's edge into the camera's opening crop. */
        fun signpostSide(tileCenterX: Float, houseX: Float, worldMode: Boolean): Float =
            if (worldMode && tileCenterX < houseX) 1f else -1f

```

  In `GardenCanvas.kt`, inside `drawMarkers`, the line
  `val px = iso.tileCenterX(v) - iso.tileW * .40f` becomes:

```kotlin
                // Signs stand on the house-facing side of their tile on the home island. Always on
                // the left, a sign on the island's left corner hung past the edge, and the home
                // camera's opening zoom cropped it (review, 2026-10-11).
                val px = iso.tileCenterX(v) + IsoMath.signpostSide(iso.tileCenterX(v), hAnchor.x, worldMode) * iso.tileW * .40f
```

- [ ] **Step 4: Run it and watch it pass.** Re-run Step 2's command without the `grep`, then
  `testcount IsoMathTest`. Expected: `BUILD SUCCESSFUL`, `11 tests, 0 failed`.

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/render/IsoMath.kt app/src/main/java/com/expensegarden/app/render/GardenCanvas.kt app/src/test/java/com/expensegarden/app/render/IsoMathTest.kt
git commit -m "fix: keep month signs on the house-facing side of their tile

Every sign stood left of its tile, so one on the island's left corner hung
past the edge, and the home camera's opening zoom cropped it. On the home
island signs now face the house; the greenhouse's cards keep the old
placement."
```

---

## Task 5: A half-filled entry survives process death

**Files:** Create `app/src/main/java/com/expensegarden/app/ui/DraftState.kt`,
`app/src/test/java/com/expensegarden/app/ui/DraftStateTest.kt`; modify `MainViewModel.kt`.

- [ ] **Step 1: Write the failing tests.** Create `DraftStateTest.kt`:

```kotlin
package com.expensegarden.app.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftStateTest {
    /** What the system hands back after process death: the same keys, in a fresh handle. */
    private fun afterProcessDeath(before: SavedStateHandle) =
        SavedStateHandle(before.keys().associateWith { before.get<Any?>(it) })

    @Test fun `a scanned draft and a manual one both survive saved state`() {
        val scanned = EntryDraft(
            fromScan = true, vpa = "shop@ybl", payeeName = "Sharma Chai", amountText = "1,200",
            categoryId = 103L, note = "masala", occurredAt = 1_760_000_000_000L, txnUuid = "uuid-1",
        )
        val manual = EntryDraft(payeeName = "Kirana", amountText = "250", occurredAt = 1_760_000_000_000L)
        for (draft in listOf(scanned, manual)) {
            val handle = SavedStateHandle()
            DraftState.save(handle, draft)
            assertEquals(draft, DraftState.restore(afterProcessDeath(handle)))
        }
    }

    @Test fun `nothing saved restores nothing`() = assertNull(DraftState.restore(SavedStateHandle()))
}
```

- [ ] **Step 2: Watch them fail to compile.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --tests 'com.expensegarden.app.ui.DraftStateTest' 2>&1 | grep -oE "Unresolved reference '[a-zA-Z]+'|BUILD [A-Z]+" | sort -u
```

  Expected: `BUILD FAILED` and `Unresolved reference 'DraftState'`.

- [ ] **Step 3: Implement `DraftState`.** Create `DraftState.kt`:

```kotlin
package com.expensegarden.app.ui

import androidx.lifecycle.SavedStateHandle

/** EntryDraft in and out of a SavedStateHandle, one key per field. Every field is a type a Bundle
 *  carries, so this needs no Parcelable and no new Gradle plugin. */
object DraftState {
    private const val PRESENT = "draft.present"
    private const val FROM_SCAN = "draft.fromScan"
    private const val VPA = "draft.vpa"
    private const val PAYEE = "draft.payeeName"
    private const val AMOUNT = "draft.amountText"
    private const val CATEGORY = "draft.categoryId"
    private const val NOTE = "draft.note"
    private const val OCCURRED_AT = "draft.occurredAt"
    private const val TXN_UUID = "draft.txnUuid"

    fun save(handle: SavedStateHandle, draft: EntryDraft) {
        handle[FROM_SCAN] = draft.fromScan
        handle[VPA] = draft.vpa
        handle[PAYEE] = draft.payeeName
        handle[AMOUNT] = draft.amountText
        handle[CATEGORY] = draft.categoryId
        handle[NOTE] = draft.note
        handle[OCCURRED_AT] = draft.occurredAt
        handle[TXN_UUID] = draft.txnUuid
        handle[PRESENT] = true
    }

    /** Null when nothing was ever saved, so a first launch starts from an empty draft. */
    fun restore(handle: SavedStateHandle): EntryDraft? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        return EntryDraft(
            fromScan = handle.get<Boolean>(FROM_SCAN) ?: false,
            vpa = handle.get<String>(VPA),
            payeeName = handle.get<String>(PAYEE) ?: "",
            amountText = handle.get<String>(AMOUNT) ?: "",
            categoryId = handle.get<Long>(CATEGORY),
            note = handle.get<String>(NOTE) ?: "",
            occurredAt = handle.get<Long>(OCCURRED_AT) ?: System.currentTimeMillis(),
            txnUuid = handle.get<String>(TXN_UUID),
        )
    }
}
```

- [ ] **Step 4: Run them and watch them pass.** Re-run Step 2's command without the `grep`, then
  `testcount DraftStateTest`. Expected: `BUILD SUCCESSFUL`, `2 tests, 0 failed`.

- [ ] **Step 5: Back the ViewModel's draft with it.** In `MainViewModel.kt`, add the imports
  `androidx.lifecycle.SavedStateHandle`, `androidx.lifecycle.createSavedStateHandle` and
  `androidx.lifecycle.viewmodel.CreationExtras`. Change the class header to:

```kotlin
class MainViewModel(private val container: AppContainer, private val saved: SavedStateHandle) : ViewModel() {
```

  Replace `    val draft = MutableStateFlow(EntryDraft())` with:

```kotlin
    /** Restored after process death: Android kills a backgrounded app freely, and a half-typed
     *  entry, or a scanned payee, used to vanish with it (review, 2026-10-11). Every change is
     *  written through to [saved], which the system persists with the activity. */
    val draft = MutableStateFlow(DraftState.restore(saved) ?: EntryDraft())

    init {
        viewModelScope.launch { draft.collect { DraftState.save(saved, it) } }
    }
```

  And in the `factory`, replace the `create` override with:

```kotlin
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                MainViewModel(container, extras.createSavedStateHandle()) as T
```

- [ ] **Step 6: Run the whole JVM suite.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest 2>&1 | grep -E "BUILD|^e: "
```

  Then `testcount ""`. Expected: `BUILD SUCCESSFUL`, `365 tests, 0 failed`.

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/com/expensegarden/app/ui/DraftState.kt app/src/test/java/com/expensegarden/app/ui/DraftStateTest.kt app/src/main/java/com/expensegarden/app/ui/MainViewModel.kt
git commit -m "fix: keep a half-filled entry across process death

The entry draft lived only in the ViewModel, so when Android killed the
backgrounded app, the restored entry screen came back empty and a scanned
payee was lost. The draft is now written through to a SavedStateHandle, one
key per field, and restored on recreation."
```

---

## Task 6: Watch each defect flip, then the docs

- [ ] **Step 1: Install the new build.** Run Task 0 Step 1's two commands again.

- [ ] **Step 2: Defect 3.** Task 0 Step 2. Expected: **no** pace line. Then set a budget
  (`u tap "Set overall budget"`, `u type "10000"`, `u tap "Save"`) and repeat the grep. Expected:
  `'on pace'`. Then `u tap "← garden"`.

- [ ] **Step 3: Defect 5.** Task 0 Step 3, saving as `t6_sign`. Expected, viewing the crop: the
  whole sign, plate and label, stands to the right of the plant and inside the screen.

- [ ] **Step 4: Defect 1.** Task 0 Step 4, saving as `t6_scan.png`. Expected:
  `pixelWidth: 1080` and `pixelHeight: 2400`.

- [ ] **Step 5: Defect 2.** Task 0 Step 5. Expected: the entry screen with `'250'` and
  `'Kirana'` in its fields. `ux.py` can't see a chip's selection, so also `u shot t6_draft` and
  view it: Groceries is the highlighted chip.

- [ ] **Step 6: Every suite.**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --rerun-tasks 2>&1 | grep -E "BUILD"
```

  Then `testcount ""`. Expected: `365 tests, 0 failed`.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest 2>&1 | grep -E "Finished|BUILD"
```

  Expected: `Finished 73 tests on Pixel_8_API_35(AVD) - 15` and `BUILD SUCCESSFUL`. Then shut the
  emulator down: `$A -s emulator-5554 emu kill`.

- [ ] **Step 7: Docs.**
  - `CLAUDE.md`: `# JVM unit tests (361)` becomes `(365)`; `# instrumented (72)` becomes `(73)`.
  - `CLAUDE.md` roadmap: `**2A sync core** (done — Go core-api + Postgres,` becomes
    `**2A sync core** (done — Go core-api + Postgres, auth hardened 2026-10-18,`.
  - `CLAUDE.md` roadmap: after `…for days it wasn't there) →` add the line
    `**the review fixes** (done 2026-10-11/18 — budget Save can't clear, home chrome readable in
    dark mode, every colour role from a token, fixed-cost detection floors at the ledger's start,
    upright scanner, drafts survive process death) →`.
  - `docs/superpowers/plans/2026-10-11-phone-e2e.md`: delete the "**The scanner opens sideways.**"
    bullet (3 lines) under "Read this first"; in Step 9, `, and how annoying the sideways scanner
    was, 1–5.` becomes `.`, and `72` instrumented tests becomes `73` (two places).

- [ ] **Step 8: Commit.**

```bash
git add CLAUDE.md docs/superpowers/plans/2026-10-11-phone-e2e.md
git commit -m "docs: update counts, the roadmap and the phone script"
```

- [ ] **Step 9: Report** the before and after for each defect, the suite counts, and the commits.
  Do not push unless asked.
