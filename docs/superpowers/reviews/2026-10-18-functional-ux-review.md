# Functional, UX, accessibility and fluidness review — 2026-10-18

At `b1077a7`, on the Pixel_8_API_35 emulator with a year-long ledger (villa.db, 100 transactions
Aug 2025–Jul 2026, migrated v2→v4 by the app) plus four October entries and a ₹15,000 budget.

**Method.** A rubric researched from primary sources (Android vitals, Core App Quality, WCAG 2.2,
Material 3, NN/g), with every threshold a finding leans on re-checked at its source. The lead
captured 70+ screens (light, dark, 200% text, landscape, every dialog and gate card) with their
accessibility trees, and measured startup and frame timing on debug and release builds. Five fresh
reviewers (money path, game/AI/settings, performance, UX heuristics, accessibility) worked from
that evidence plus the code. **Every finding below was verified by the lead** against the code,
the device, or a measurement — marked ✔code, ✔device or ✔measured. Severity is NN/g's 0–4.

Caveats: the emulator is not a phone (Google discourages emulator benchmarks), and the host Mac
was under unrelated load, so janky-frame percentages are reported as ranges and not used as
findings. UI-thread timings are app CPU work and are used. The gate cards were captured on a
throwaway local build that routed manual entry through the gate (the emulator can't show a QR);
their content and layout are the real code.

## Verdict

- **Fluidness is fine on a release build and not on the debug build the phone gets today.** The
  garden's UI-thread work is ~3.6 ms per frame (release, compiled) vs ~20 ms (debug) against a
  16.7 ms budget. Startup passes Android vitals in every configuration.
- **The month boundary is the biggest functional risk.** Budgets lapse every 1st (the gate goes
  silent), and re-entering one mints streaks and rares for days nobody earned.
- **The gate isn't always truthful at the moment of decision** — streak claims, overage numbers
  and quips can all be wrong.
- **Mistakes are permanent.** No edit, delete or undo anywhere on the money path.
- **Accessibility:** fine on contrast and target size for real controls, weak on semantics — the
  garden is invisible to TalkBack and nothing is announced.

## Fluidness numbers (emulator, 60 Hz)

| | Debug (what `installDebug` gives) | Release, after ART's profile compile |
|---|---|---|
| Cold / warm / hot start (median) | 594 / 138 / 102 ms | 232 / 56 / 85 ms |
| Garden UI-thread draw, p50 / p90 | 19.6 / 31.0 ms | 3.4 / 4.8 ms |
| Garden UI-thread total, p50 / p90 | 20.4 / 32.0 ms | 3.6 / 5.2 ms |
| Recompose / layout per frame | 0.54 / 0.04 ms | 0.20 / 0.04 ms |
| Memory (PSS) | 134–143 MB | 111 MB |

Android vitals call startup excessive at ≥5 s cold, ≥2 s warm, ≥1.5 s hot: every configuration
passes by an order of magnitude. ART refused to AOT-compile the debuggable build (`verify` after a
forced compile). The garden animates continuously only while home is on screen: the frame clock
pauses when the app is backgrounded and the scene freezes under the system "Remove animations"
setting (WCAG 2.2.2 is met through the OS). The ~25 ms render-thread phase is the emulator's GPU
translation (identical on the plain dashboard) and is not an app finding. Re-measure on the phone
with `perf.py` in the session scratchpad (`SERIAL=<serial> perf.py phases idle_home_10s`).

## Findings

### Severity 3 — fix before the first full month of real use (from 1 Nov)

**R1. Budgets lapse on the 1st; the gate goes silent until each is re-entered.** ✔code
Budgets are read for the exact month (`Daos.kt:164-168`); with none, `scopeInputs` returns
nothing (`LedgerRepository.kt:127-128`) and every payment is OK — indistinguishable from "on pace".
No code carries budgets forward ("budget rollover" was deferred in the 1B spec, line 12).
*Fix:* copy the latest month's rows into an empty month once, idempotently (e.g. in the reconciler).

**R2. Rewards are granted for days nobody earned.** ✔device
A budget has no start date (`Entities.kt:89-95`), so setting one on day N re-scores days 1..N−1
(`StreakMath.kt:27-31`); StreakBaseline only asks when the ledger began, not whether it was used.
On the device: ₹15,000 set at 13:22:56, and at **13:24:29** the reconciler wrote `streak.hit` 3, 7
and 14 — for 17 October days with no budget and no transactions (STREAK_7 is a permanent rare).
Related: the no-spend floor applies only to the current month and not at all in the album
(`GardenFolder.kt:230`, `GardenRepository.kt:164`); Regret → Worth it mints a REDEEMED rare on any
purchase, necessities included (device: "3 waiting" → "4 waiting" in seven seconds;
`RareEngine.kt:85-92` limits it per purchase, not across purchases).
*Fix:* record budget changes as events and score each day only against the budget in force; the
unused-ledger rule needs a ruling (below).

**R3. The gate's persuasion isn't always true.** ✔code ✔device
- "This ends a 17-day streak" (gate_pace) when the month was already over the line before the
  payment — the streak was lost either way (`GatePresentation.kt:52`, `StreakMath.kt:27-31`).
- Any category-scope pace warning shows the *overall* streak with no scope label
  (`GatePresentation.kt:133-138`, `MainViewModel.kt:154`); `GatePresentationTest.kt:72-76` pins it.
- "₹X over today's ₹Y" uses the allowance without fixed costs (`MainViewModel.kt:161-164`, the
  3-argument overload at `GateEvaluator.kt:12-13`) while the verdict excludes them
  (`GateAggregator.kt:28`); the streak ignores them too (`StreakMath.kt:31`). With rent detected,
  the card overstates the overage many times over, and the streak can't build.
- Quips are chosen by severity alone: "Budget's already gone. This is just archaeology now."
  appeared when ₹4,250 was left and this payment crossed it (gate_breach_dark); the PACE bank has
  "This one's fine. The next three are the problem." (`AppDatabase.kt:82`).
*Fix:* pass spend-before and the fixed term into GatePresentation; claim "ends a streak" only for
the overall scope when before ≤ line < after; split crossing vs already-over quips.

**R4. Mistakes are permanent.** ✔code ✔device
Transactions only ever get a status or regret update (`Daos.kt:66`, `:98`); the plant and Recent
sheets offer Worth it / Regret / Close only. Discard is instant with no undo (after_discard), and
the pending card names neither payee nor time — "Did ₹1 go through?" (`GardenHomeScreen.kt:198`).
`confirm()` doesn't check the row is still pending (`LedgerRepository.kt:43-48`), and nothing
guards a double tap: "Continue to pay" twice re-inserts the same uuid (`@Insert`, abort) and
crashes mid-payment.
*Fix (minimum for November):* "₹X to <payee> · <time>" on the card, an Undo snackbar after Log it
and Discard, busy guards, and status-guarded `setStatus`. Full edit/delete needs a ruling.

**R5. Restore can wipe the phone.** ✔code
`restore()` deletes events, budgets, transactions and payees, then inserts whatever the server
returned — even an empty snapshot (`SyncRepository.kt:67-83`); `isLedgerEmpty()` exists and is
never called; the confirm dialog shows no counts. Pointing the app at 2B's fresh Neon database and
tapping Restore to "test" it would erase the ledger. *Fix before 2B:* refuse an empty snapshot over
a non-empty ledger and show real counts, including unsynced changes.

**R6. The phone's daily build is the debuggable one.** ✔measured
See the table: the garden's UI thread alone exceeds the frame budget in debug. `profileinstaller`
1.3.1 and a 7.6 KB baseline profile already ship in the APK, so no new dependency is needed.
*Fix:* `signingConfig = signingConfigs.getByName("debug")` in `buildTypes.release`, then
`installRelease` (same key, so it updates in place). Caveats: `run-as` stops working (the phone
E2E's ledger pull uses it — run the E2E first), and the debug-only cleartext config goes away.

**R7. TalkBack can't use the garden, and nothing is announced.** ✔code ✔XML
The canvas is two unlabelled full-screen nodes; plant taps are pointer-only; the only other path to
a plant's sheet is Recent, which is capped at 50 (`Daos.kt:91`) — every rent payment is older.
No `liveRegion`, `paneTitle` or `heading()` anywhere: errors, the pending card, Log it and Discard
are silent. (Severity 3 by WCAG; low practical urgency for you as a sighted user, but it matters
for the Play Store/portfolio goal.)

### Severity 2

| ID | Finding | Evidence |
|---|---|---|
| R8 | The necessity card's "Back" abandons the scan (pops to home) though the spec says it "only dismisses" — the user usually wants to fix the amount | ✔ `EntryScreen.kt:268-272,351`; spec 2026-09-17:49,54 |
| R9 | Weeds without a pre-payment choice: any manual log that crosses the budget plants one, silently (incl. a backdated entry judged against later spending); and weeds never compost (parent spec §9 promised it) | ✔ `MainViewModel.kt:179-181`, `LedgerRepository.kt:131-133`, `PlantMapper.kt:70`; parent spec:228 |
| R10 | Pending payments count nowhere until answered, so a multi-scan shopping trip is judged without its earlier scans | ✔ every sum filters `status='LOGGED'` (`Daos.kt:72-115`) |
| R11 | Fixed-cost detection is too broad (a café paid once a month for two months becomes "fixed") and brittle (cash payees match on exact, untrimmed, case-sensitive name) | ✔ `RecurringPayees.kt:73-76,89-92`; `Daos.kt:34` |
| R12 | Date-dependent state refreshes only on a cold start (reconciler and AI job run in ViewModel `init`); a streak.hit can be missed for good | ✔ `GardenViewModel.kt:71-73`, `AiViewModel.kt:40`; no lifecycle hooks |
| R13 | The home strip reads as good news when the month is going wrong: "ahead of pace · 🌱17d", untinted, with a streak that already failed today | ✔ device; `GardenHomeScreen.kt:144-150` |
| R14 | Greenhouse month view: system Back exits to home, taps and swipes fall through to the hidden list, no month title; dark-mode month labels ~2.0:1, "← back" 3.8–4.1:1 on the sky | ✔ device (Back), greenhouse_dark crop, measured |
| R15 | Backup status can't tell off / 401 / bad URL / offline apart; Save tests nothing; non-2xx isn't logged | ✔ `SyncClient.kt:80`; `SettingsScreen.kt:229-240` |
| R16 | A new Gemini key or voice does nothing until a cold start, a bad key is indistinguishable from a good one, and Voice isn't kept unless you scroll to Save | ✔ `AiViewModel.kt:40,60`; `SettingsScreen.kt:127,185` |
| R17 | **Month signs overlap (OCT on JUL) — a regression from this morning's `d026a0c`**: signs left of the house's centre line moved right, the one just right of it didn't | ✔ A/B builds: signs_crop_prefix vs signs_crop_postfix |
| R18 | The garden re-records static ground every frame (~1,780 draw ops; per tile two Paths, a gradient and a DashPathEffect) and re-measures month labels every frame | ✔ `GardenCanvas.kt:891-908,261,719` (share of the 3.4 ms unmeasured) |
| R19 | The all-time garden fold runs on the main thread on every ledger write and when the greenhouse opens (no `flowOn` anywhere) | ✔ code; cost unmeasured |
| R20 | Light-mode text on cards/dialogs below AA: "Budget: ₹15,000" 4.01:1, "ahead of pace" 4.10:1, dialog actions 4.24:1, Discard 4.01:1; selected chips are a 1.1–1.3:1 tint with no checkmark; category pace by colour only | ✔ measured; 0 `leadingIcon` across 6 FilterChips |
| R21 | Landscape dashboard leaves a 77 px scrolling window; plant tap targets are the ground tile, not the drawn sprite; pan/zoom only by gesture | ✔ dashboard_landscape XML; `GardenCanvas.kt:239` |
| R22 | Sync identity: payee and event ids are device-local, but the server keys on them — a replacement phone that logs before restoring relabels server history and drops events. Latent until 2B | ✔ `Entities.kt:39`; `store.go:101-108,144-147`; backup excludes the cursors |

### Severity 1

Floats in the pace allowance (₹17,249.99 instead of ₹17,250; `GateEvaluator.kt:33`) · forecasts
to the paisa, and "₹0/day keeps you under" once over budget (`PaceProjector.kt:34`) · dates without
a year on an all-time island; the all-time Recent list on a "This month" screen · "your next
purchase will grow one" (seeds only land on a matching, non-weed plant) · sprites decoded at full
512 px (54 MiB for art drawn ≤273 px) · house sprites decoded on the main thread in the first
composition · the house-expansion animation recomposes the canvas every frame · date picker has OK
but no Cancel · the overall-budget dialog has three actions with destructive Clear beside Cancel ·
dashboard rows 34dp and tone rows 40dp (under 48dp) · zxing's "Scan a UPI QR" prompt sits under the
navigation bar.

## Rulings needed from you

1. **Budget rollover:** copy last month's budgets forward automatically, or ask on the 1st?
2. **Days the app wasn't used:** should they count toward streaks and no-spend rares?
3. **Weeds:** build compost (a weed folds back after a clean week), or amend "every bad state has a
   redemption path"?
4. **Manual-log weeds:** keep (1B spec) or drop (1C spec: weeds "only from pre-payment breach")?
5. **Correcting mistakes:** undo only for November, or full edit/delete now (it adds sync events)?
6. **Daily build:** move the phone to a debug-signed release build after the E2E run?

## Checked and fine

Startup passes vitals everywhere · Room never on the main thread · lazy lists keyed · process death
safe on the money path (pending saved before the intent; navigation pops before state is saved) ·
migrations 1→4 preserve data (the v2 ledger migrated cleanly) · sync cursors advance only on 2xx,
last-write-wins is idempotent · necessities are never shamed (neutral card, no quip, no weed) · the
weed the gate previews is the weed that grows · secrets are masked and excluded from cloud backup ·
the LLM is never in a read path · validation shows all errors at once and keeps input · text
contrast ≥4.5:1 everywhere outside R14/R20 · every real control's touch target ≥48dp · no clipping
at 200% text · state survives rotation and theme changes (a half-typed entry survived a dark-mode
switch).

## Rejected or adjusted

- "Settings' back link reads ← back" (lead) — wrong: Settings says "← garden"; the "← back" belongs
  to the greenhouse month overlay, where it is correct.
- "Log it is off-screen at 200% text" (lead) — overstated: it is reachable by scrolling with the
  keyboard up.
- The duplicate `transaction.logged` events from a double "Log it" are harmless to the garden
  (plants fold from transaction rows); they only reach the server replica.

Artifacts (screens, dumps, measurements, the rubric) are in the session scratchpad under `rev/`.
