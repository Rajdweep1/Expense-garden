# The Gate — Previewing the Consequence: Design

**Status:** approved in conversation 2026-09-17.

**Goal:** Make the pre-payment gate do the three things the parent spec promised and the shipped
code never delivered — stop shaming necessities, preview the consequence of the purchase, and put
the emphasis on the choice the app exists to encourage.

**Parent spec:** `docs/superpowers/specs/2026-07-03-expense-garden-design.md` §5.1 (*"the resident
character delivers one line + two buttons"*), §5 Blocking (*"persona gate previews consequence
('this plants a weed'), user can always proceed"*), §10 Boundaries (*"necessities off-limits …
never mocked"*).

---

## 1. Three gaps, measured against the code

The gate is `EntryScreen.kt:247` — a stock `AlertDialog` with a title, the cached quip, and two
`TextButton`s.

| # | Promise | What ships |
|---|---|---|
| 1 | "necessities off-limits, never mocked" (§10) | **No necessity check exists anywhere at gate time.** `LedgerRepository.evaluateGate`, `GateAggregator` and `GateEvaluator` never read `isNecessity`. A breaching Health payment gets the same roast as a shopping spree. |
| 2 | "previews consequence — *this plants a weed*" (§5) | Nothing is previewed. The dialog shows a text quip only. |
| 3 | emphasis on backing out | Material 3 renders `confirmButton` rightmost and primary, so **"Pay anyway" reads as the recommended action** in an app built to discourage it. |

Gap 1 is the notable one: **the garden already gets this right and only the gate is out of step.**
`PlantMapper.kt:69-70` gates both the zombie and the weed on `!ownNecessity`, so a breaching
necessity has never grown anything ugly. The punishment the gate threatens does not exist.

The resident character (§5.1) is the fourth gap and is **out of scope** — it needs new art and a
decision the parent spec itself deferred ("species TBD — crow vs gnome").

## 2. The rule

One branch on necessity, then on severity.

| Case | Preview | Copy | Primary button | Secondary |
|---|---|---|---|---|
| `OK` | — | — | no dialog (unchanged) | — |
| Necessity, either severity | none | neutral, factual, **no quip** | **Continue** | Back |
| `BREACH` + discretionary | the actual weed sprite | quip | **Not now** | Pay anyway |
| `PACE_WARNING` + discretionary | the streak at risk | quip | **Not now** | Pay anyway |

The proceeding button runs `fireAndFinish` in every case. The other one differs:

- **Discretionary — "Pay anyway" / "Not now".** "Not now" runs `recordDodge` and returns home,
  exactly as "Nope, saved" does today.
- **Necessity — "Back" does NOT record a dodge.** It only dismisses.

That second rule fixes a farm that exists in the shipped build. `GATE_DODGES` earns a rare for
"backing out at the gate", and today a breaching necessity shows the gate with a dodge button —
so opening a chemist payment and backing out three times earns a rare. Backing out of a necessity
is not restraint; it usually means the amount was wrong. 4A's rule is that rares are *earned by
restraint, never by spending*, and a necessity dodge is neither.

Necessities take one shape at both severities rather than two. A chemist run that is merely early
and one that breaches differ by a number, not by kind, and a second shape would only add a rule to
remember.

## 3. What each case previews, and why it differs

The three cases have genuinely different stakes, so a uniform preview would misrepresent two of
them.

**`BREACH` + discretionary — a weed, permanently.** This is the only case where the spec's original
line is literally true. `breachedAtLogging` flows into `PlantMapper`, which grows
`THISTLE_WEED` or `ODD_MUSHROOM`. The dialog shows that sprite.

**`PACE_WARNING` + discretionary — the streak.** Nothing ugly grows here; the plant is ordinary. But
`PACE_WARNING` is defined as `afterPayment > paceAllowance`, which is *exactly* the condition that
makes today fail its pace check. So the real cost is the under-pace streak, and with it the
`STREAK_7` / `STREAK_30` rares.

One honesty note: `StreakMath.underPaceStreak` counts only `(1 until today)` — fully past days — so
the streak does not break at the moment of payment. It breaks tomorrow, when today is evaluated.
The copy must not claim otherwise; "this ends a 9-day streak" is a statement about what follows,
and is true.

**Necessity — nothing in the garden.** No weed, no zombie. The only real effect is on the number,
so the number is all the card shows.

### 3.1 The preview must be the actual weed, not a generic one

Which weed grows is `abs(seed) % 2` where `seed = txn.uuid.hashCode()` — and the UUID is minted
inside `LedgerRepository.save()`, after the gate has already been answered. Previewing a thistle
and growing a mushroom is a small lie, and this app's stated rule is that the garden cannot
misreport what was bought.

So the UUID is minted when the gate is prepared and carried on the draft into `savePending`.
`save()` takes it as a parameter instead of generating its own.

## 4. Buttons

On discretionary cases **"Not now"** becomes the filled button and **"Pay anyway"** a plain text
button beneath it. On necessities this inverts: **Continue** is filled, because you should pay the
chemist.

**Why this is not a dark pattern.** A dark pattern works against the user's interest. Both buttons
stay visible, same tap target, no delay, no disabled state, no double-confirm, no pre-selection.
The only change is which one is visually louder — and the user installed a spending-control app, so
emphasising restraint serves the goal they set. The parent spec's "user can always proceed" is
preserved exactly.

**"Nope, saved" becomes "Not now".** The app cannot verify that anything was saved; the user may
buy the same thing ten minutes later somewhere else. The `recordDodge` call behind the button is
unchanged, so `GATE_DODGES` still earns its rare — that trigger is about backing out at the gate,
which is what actually happened.

## 5. The streak baseline

The pace preview prints a streak, which makes an already-known-soft number load-bearing in the most
emotionally charged screen in the app.

`underPaceStreak` credits every elapsed day of the current month against an empty ledger. On a
fresh install on the 13th it returns 12. That is the `🌱12d` on the home strip, and it is also why
`RareDeterminismTest.two_dodges_grow_no_rare` and
`re_tagging_a_regret_repeatedly_still_grows_only_one_rare` fail on any date from the 8th onward —
`STREAK_7` fires and grows a rare the test never asked for.

**Fix:** `underPaceStreak` gains a `firstObservedDay` parameter and counts only days `>=` it.

**Where the date comes from.** Three cases have to work, and no single source handles all three:

| Case | Right answer | Ledger-only gets it | Pref-only gets it |
|---|---|---|---|
| Fresh install on the 13th, nothing logged | streak 0 | ✓ | ✓ |
| Installed the 1st, genuinely spent nothing until the 20th | streak earned from day 1 | ✗ truncates to day 20 | ✓ |
| Restored onto a new phone with full history | streak from the real history | ✓ | ✗ truncates to today |

So it is the earlier of the two:

```
firstObservedDay = min(GardenPrefs.firstObservedAt, earliest game_event createdAt)
```

`GardenPrefs` is the right home for the device half — it already exists for exactly this kind of
device-local view state, and its own doc comment explains why such state must not go in Room and
sync. The ledger half needs no new state at all. No schema change, so no Room migration.

## 6. Architecture

**`gate/GatePresentation.kt` (new, pure Kotlin).** Maps
`(severity, isNecessity, streakDays, spentPaise, budgetPaise, candidatePaise, weedKey)` to a sealed
`GateView`:

```kotlin
sealed interface GateView {
    data class Weed(val archetype: Archetype, val variant: Int, val quip: String, val scopeLabel: String?, val overPaise: Long) : GateView
    data class Streak(val days: Int, val quip: String, val overPaise: Long, val allowancePaise: Long) : GateView
    data class Neutral(val scopeLabel: String, val afterPaise: Long, val budgetPaise: Long) : GateView
    data object None : GateView
}
```

No Android imports, so every branching rule is JVM-testable — the same reason `CollectionSummary`
and `ContrastMath` are shaped this way. The dialog becomes a dumb renderer of whichever `GateView`
it is handed.

**`ui/MainViewModel.kt`.** `prepareGate` additionally reads `isNecessity` from the already-exposed
`categories` StateFlow, reads the current streak, and mints the draft UUID. `GatePrompt` is replaced
by `GateView`.

**`data/GardenRepository.kt`.** There is no accessor for "today's streak" — the only live
computation is inlined in `runReconciler` (line ~205). Add
`suspend fun currentUnderPaceStreak(): Int` and have the reconciler call it, so the day-totals and
overall-budget lookup exists once rather than twice. The gate calls it the same way it already
calls `evaluateGate`: a suspend repo call, off the main thread, no new plumbing into the ViewModel.

**`ui/EntryScreen.kt`.** A custom `Dialog` replaces `AlertDialog`, painted with `GardenTheme`'s
scheme. The sprite is decoded by `SpriteLoader.decodePlant(context, archetype, variant)` inside a
`LaunchedEffect` — the single-sprite decoder added during the sprite-memory work, so no canvas and
no eager pack load. **If the sprite is absent the image slot is simply omitted**; the copy already
names the consequence, so a missing asset degrades to text rather than to a broken layout.

**`game/StreakMath.kt`** and its three call sites — `GardenFolder.kt:69`, `GardenFolder.kt:170`,
`GardenRepository.kt:205`.

## 7. Testing

**JVM (`app/src/test`)** — all of it, because the branching lives in pure Kotlin:

- `GatePresentationTest` — every `severity × isNecessity` combination maps to the expected `GateView`;
  `OK` always yields `None`; **a necessity never yields a quip** (the §10 boundary, encoded as a test
  rather than a convention); **a necessity never offers a dodge** (§2, so the farm cannot come back);
  a breach picks the same weed the `PlantMapper` seed rule would.
- `StreakMathTest` — days before `firstObservedDay` never count; a fresh mid-month install returns 0;
  an install-on-the-1st with no spend until the 20th still returns the earned streak.

**Instrumented** — no new tests. The two failing `RareDeterminismTest` cases should pass at any date
once §5 lands; that is the check that the baseline fix actually worked.

## 8. What this deliberately does not do

| Deferred | Why |
|---|---|
| The resident character | Needs new art and a species decision the parent spec itself left open. The gate can be honest before it has a face. |
| Changing which severities fire | The thresholds (`PACE_GRACE = 1.15`, fixed-cost exclusion) were tuned against real spending and are not in question. This changes what the dialog *says*, not when it appears. |
| Reworking the quip bank | The cached-quip mechanism and its offline guarantee stay exactly as they are. Necessities simply do not ask for one. |
| A confirm step on "Pay anyway" | That would cross from emphasis into obstruction, and the parent spec's "user can always proceed" is explicit. |

## 9. Risks

- **The streak fix touches the fold**, which is the app's most load-bearing pure function. It is
  covered by JVM tests and the change is additive (a new parameter with the existing behaviour at
  `firstObservedDay = 1`), but this is the part to review carefully.
- **Minting the UUID earlier** moves identity generation out of the single place that owned it.
  `save()` must still generate one when no UUID is supplied, so the manual path is untouched.
- **The gate becomes taller.** Three text elements plus an image, against a parent spec that says
  "one line + buttons, never a lecture". Read as "do not moralise at length" rather than a literal
  one-line cap; the metadata line is a 12px caption, not a sentence. If it reads as a lecture on
  device, the caption is the first thing to cut.
- **Nothing here touches money, events, or the fold's output** — only which dialog is shown before a
  payment the user still controls.
