# Phase 1D — AI Layer (design)

**Status:** approved by Rajdweep 2026-09-04; **revised 2026-09-04** after an adversarial review (see §13)
**Predecessor:** [1C.7 Growing Homestead](2026-08-16-1c7-homestead-growth-design.md) — complete, merged to main, tagged `v0.1-garden`
**Parent spec:** [expense-garden design](2026-07-03-expense-garden-design.md) §4 (LLM), §8.4 (quip cache), §5.3 (digest), §10 (persona boundaries)

## Goal

Give the garden a voice. Two surfaces, both driven by a swappable LLM client on the Gemini
free tier at ₹0/month: the gate's quip bank refreshes itself instead of drawing on a fixed
seed list, and a resident persona speaks — daily when something changed, monthly when a
month closes.

## Decisions taken (and the ones rejected)

| Question | Decision | Rejected |
|---|---|---|
| Which of the five AI surfaces ship | **Quip refresh + digest** | quip refresh alone; adding free chat |
| Digest cadence and surface | **Both** — daily card on open, monthly card on the greenhouse postcard | monthly only (my recommendation); daily only |
| API key storage | **In-app settings screen**, app-private prefs | `local.properties` → BuildConfig; both with precedence |
| Daily silence rule | **State transitions only** | salience score; transitions + cooldown |
| Quip bucketing (§6) | **severity × tone** | severity alone (the approved draft — wrong, see §13); full severity × category-family × tone |
| Backup posture (§2.1) | **Exclude the AI prefs file, keep ledger backup** | `allowBackup="false"` — would delete the only device-loss protection |

## §1 The load-bearing principle

**The LLM is never in a read path.**

Quips are read from the `quip` table. Digests are read from the `digest` table. The LLM only
ever *writes* to those tables, from a background job. There is no code path from a user action
to an HTTP call.

This makes "the gate never blocks on an LLM call and works offline" (parent spec §5.1) a
**structural property rather than a promise defended by timeouts**. It cannot regress by
accident — a future contributor would have to deliberately introduce a call site. Offline
degrades to *stale content*, never a spinner and never a broken screen.

It is also the same shape the rest of the app already uses: the append-only `game_event` log
is the truth, and everything else is a fold or a cache over it.

**This was already anticipated.** `QuipEntity` carries `origin` defaulting to `"STATIC"` with
the comment *"LLM refresh comes in 1D"*, and `QuipRepository.pick()` is a pure LRU read off the
local table. The gate's call path needs no change; §6 adds one column and one `WHERE` clause.

## §2 Zero new dependencies

The pinned matrix in `gradle/libs.versions.toml` has no HTTP client, no serialization library
and no WorkManager. 1D adds none:

| Need | Solution already available |
|---|---|
| HTTP POST | `java.net.HttpURLConnection` — framework |
| JSON | `org.json` — framework, already used in `GardenRepository` and `LedgerRepository` |
| Background trigger | on-open coroutine, the `runReconciler()` idiom in `GardenViewModel.init` |

This is a deliberate constraint, not an accident of scope. The repo's stated thesis is
restraint over sprawl; adding Retrofit, OkHttp and kotlinx-serialization to make one JSON POST
would undercut the argument the codebase is making.

**The exact model and endpoint are pinned in the plan, not here — but the ₹0 claim depends on
them.** The plan's first AI task must name the model, name the endpoint, record its free-tier
quota, and check 1D's actual call volume against it: at most one refresh per depleted bucket per
day, plus at most one digest per day. Parent §8.2 budgets 20–50 calls/day against the free-tier
ceiling — 1D's two surfaces must be *shown* to fit inside that, not assumed to.

**Accepted consequence:** Android's proper secret store, `EncryptedSharedPreferences`, needs
`androidx.security.crypto` and is therefore unavailable. The key sits in plain app-private
storage. That is proportionate for a free-tier key with no billing attached — the blast radius
of a leak is a rate limit, not a bill — **but it changes the moment billing is attached to the
key**, and that trade-off should be revisited then.

### §2.1 Manifest changes — the phase does not work without these

The app has never made a network call. `AndroidManifest.xml:4` declares `CAMERA` and nothing
else, and no dependency AAR contributes a permission (verified: zero `INTERNET` occurrences in
the merged manifest). Three changes are required:

| Change | Why |
|---|---|
| `<uses-permission android:name="android.permission.INTERNET" />` | Without it every call throws `SecurityException`/`SocketException` |
| `android:dataExtractionRules` (API 31+) excluding the AI prefs file | Auto Backup would otherwise copy the API key to Google's cloud |
| `android:fullBackupContent` excluding the same file | Same, for API 26–30 — `minSdk` is 26, so both attributes are needed |

**`allowBackup` stays `true`.** It is currently unset, which defaults to true, and until the
Phase 2 backend exists Auto Backup is the *only* thing standing between a lost phone and a lost
ledger. Turning it off to protect a free-tier key would trade a rate limit for the user's data.
Exclude the one file instead.

**This is the phase's most dangerous failure mode, so it is called out here rather than left to
the plan.** §9 says every failure degrades to silence and no error state is rendered — which
means a missing `INTERNET` permission is *indistinguishable from the documented "no API key"
state*, and §11's airplane-mode test passes against a permanently dead client. 1D could ship
100% non-functional and fully green. The plan must verify a real 200 response on device before
any other AI task is marked complete.

## §3 Components

| File | Responsibility |
|---|---|
| `ai/LlmClient.kt` | `suspend fun complete(prompt: String): String?` — returns null on *any* failure. Plus `NoopLlmClient` returning null always. |
| `ai/GeminiClient.kt` | The single implementation. `HttpURLConnection` + `org.json`, **wrapped in `withContext(Dispatchers.IO)` inside `complete()`**. Deliberately the thinnest file in the layer, because it is the only one that cannot be unit-tested offline. |
| `ai/Persona.kt` | System prompt, the §10 boundaries, and the three tone presets. Pure strings — testable. |
| `ai/PromptFacts.kt` | The **only** type allowed into a prompt. Closed enums and numbers, no free text. See §10. |
| `data/AiPrefs.kt` | API key, tone, last quip-refresh timestamp, `mutedUntil`. Its own SharedPreferences file. |
| `data/DigestRepository.kt` | Owns the `digest` table: reads the watermark row, writes new ones, parses `snapshotJson`, and projects `game_event` rows into typed `DigestEvent`s (§5). **All JSON lives here, never in the trigger.** |
| `ai/QuipRefresher.kt` | Tops up low-stock (severity × tone) buckets, sanitizes output, inserts rows with `origin = "LLM"`. |
| `game/DigestTrigger.kt` | **Pure fold** → `DigestVerdict` (§5). Contains the entire silence rule. No LLM, no IO, **no `org.json`**. |
| `ai/DigestWriter.kt` | Reason + `PromptFacts` → prompt → `LlmClient` → **text, or null**. Never writes: the job composes every reason first and hands them to `DigestRepository.writeAll()` in one transaction (§9). |
| `ui/SettingsScreen.kt` | Key field + tone knob. First settings surface in the app. Needs a `NavHost` route (`MainActivity.kt:72`) and an on-screen entry point. **There is no top bar to hang it on** — `GardenHomeScreen` is a deliberately chrome-free `Box(Modifier.fillMaxSize())` with navigation hand-placed over the canvas, so this is a second absolutely-positioned control beside the greenhouse button, matching its manual `statusBarsPadding()` + hardcoded offset. |

**That entry point is the gate on the whole phase.** The API key can only be entered through it,
and §11 says an app with no key is "fully functional" — so a settings screen that is hard to reach,
or reached only from a screen that does not exist, produces exactly the silent-nothing state §2.1
warns about. It is not UI polish to be deferred.

`AiPrefs` is deliberately a separate file from `GardenPrefs`. Both are device-local and must
never sync to the Phase 2 backend, but mixing a **secret** with **view state** in one store
muddies the reasoning about each. One file, one kind of thing.

**Dispatcher:** `GardenViewModel.init` launches on `viewModelScope`, which is
`Dispatchers.Main.immediate`. Room's suspend DAOs dispatch internally and are safe there;
`HttpURLConnection` is not, and throws `NetworkOnMainThreadException`. The `withContext` goes
*inside* `GeminiClient.complete()` rather than at the call sites, so no future caller can get it
wrong — the same reasoning as §1.

## §4 Data flow

```
app open ─→ runReconciler()                    (already exists)
        ─→ QuipRefresher.refresh(tone)         caller throttles to once/day via AiPrefs.lastQuipRefreshAt;
                                               the refresher itself only checks bucket stock
        ─→ DigestTrigger.evaluate()            PURE — returns a DigestVerdict; silence = empty
              └─ each reason  ─→ DigestWriter ─→ digest table   (0..1 DAILY, 0..n MONTHLY)
                                                       ↓
   GardenHomeScreen  reads latest undismissed DAILY  ─→ dismissible card
   GreenhouseScreen  reads the month's MONTHLY       ─→ card on that month's postcard
```

## §5 The silence rule (`DigestTrigger`)

The daily digest speaks **only when something transitioned** since it last spoke. Ordinary days
produce silence automatically — not because a tuned threshold wasn't met, but because nothing
changed. Signals, all of which already exist:

| Trigger | Kind | Source |
|---|---|---|
| Weather changed (SUNNY ↔ OVERCAST ↔ DROUGHT) | comparison | `GardenState.weather` vs last snapshot |
| House levelled up | comparison | `GardenState.houseLevel` vs last snapshot |
| Streak threshold crossed | event | `streak.hit` |
| First regret of the month | event | `transaction.regretted` + `monthToDate.regretCount` |
| Gate dodged | event | `gate.dodged` |
| Month closed | event | `month.closed` → fires the **MONTHLY** digest instead |

**The weather trigger can fire on a day the user spent nothing.** `PACE_WARNING` is
`spent > budget × dayOfMonth / daysInMonth × 1.15` (`gate/GateEvaluator.kt:9-11`), so the
allowance grows every midnight while spend stands still — a quiet day can flip OVERCAST → SUNNY
unaided. It is rare (a few times a month, and only with lumpy spending: at a constant rate both
sides of that comparison are linear in `dayOfMonth` and never cross) and it is **always good
news**, because `BREACH` has no day term and DROUGHT therefore never clears this way. The
writer cannot know whether spend moved — the snapshot carries no spend — so `DigestWriter`
tells the model the improvement *may* be a calendar effect and forbids "you spent differently".
Hedging honestly beats asserting what the system does not know.

`gate.dodged` is a **win** and must read as one. It records the user backing out at the gate;
the persona celebrates it. This is the "never punish the log" invariant applied to the voice.

**Signature — pure, no IO, no LLM, no JSON:**

```kotlin
fun evaluate(
    lastDigest: DigestSnapshot?,          // null = has never spoken
    eventsSince: List<DigestEvent>,       // typed, id-ordered; strictly id > lastDigest.lastEventId
    monthToDate: MonthFacts,              // regretCount; monthKey is identity for traceability, unread here
    now: GardenState,
    today: LocalDate,
    mutedUntilMillis: Long?,              // "not today" — see §8
    nowMillis: Long,                      // injected, not read — keeps the fold deterministic
): DigestVerdict                          // never null; silence is daily=null, monthly=[]

data class DigestVerdict(
    val daily: DigestReason?,             // null = silence
    val monthly: List<DigestReason>,      // one per closed month, oldest first
)
```

Time crosses the boundary as **epoch millis, not `Instant`**: that is what `AiPrefs` stores
and what `game_event.createdAt` already uses, and injecting `nowMillis` rather than reading the
clock is what keeps the fold deterministic under test.

**The window is bounded by `game_event.id`, not by a timestamp** — see §9 for why a
`createdAt` watermark is unsound.

**`DigestEvent` is a typed projection of `game_event`, not the row itself.** A `month.closed`
row carries its month inside `payloadJson`, so a trigger taking raw entities would have to parse
JSON to know which month it is speaking about — contradicting §12's requirement that the trigger
stay `org.json`-free and therefore JVM-testable. `DigestRepository` parses at the boundary and
hands the trigger facts:

```kotlin
sealed interface DigestEvent {
    val id: Long
    data class MonthClosed(override val id: Long, val monthKey: String) : DigestEvent
    data class StreakHit(override val id: Long, val days: Int) : DigestEvent
    data class GateDodged(override val id: Long) : DigestEvent
    data class Regretted(override val id: Long) : DigestEvent
}
```

**The reconciler's outputs are plural, so the verdict is too.** `Reconciler.decide` returns
`monthsToClose: List<String>` *and* `streakHitsToEmit: List<Int>` (`game/Reconciler.kt:19-23`),
and `runReconciler()` inserts **one event per element** (`data/GardenRepository.kt:110-119`). One
open after a long gap can emit four `month.closed` rows and four `streak.hit` rows at once. A
signature returning a single nullable reason would speak about one of them and advance the
watermark past the rest — and the loss would be **permanent**, because the reconciler's own
idempotence filter (`it.toString() !in closedMonths`) guarantees it never re-emits. So:

- **`month.closed` → one MONTHLY digest per closed month.** §9's `UNIQUE(kind, scopeKey)` always
  allowed this; only the return type stood in the way.
- **`streak.hit` → the highest threshold only.** Crossing 3, 7, 14 and 30 in a single open is one
  achievement, not four; the persona congratulates the 30 and ignores the rest.

`monthToDate` exists because **"first regret of the month" is not computable from
`eventsSince`** — that list spans only since the last digest, so a regret logged last week would
be invisible and today's second regret would be miscounted as the first. It is a small value
type of precomputed scalars, so the function stays pure.

**First run (`lastDigest == null`): comparison triggers stay silent; event triggers fire.**
Weather and house level have no baseline, so claiming "the weather changed" on first open would
be a fabrication. Events are self-contained facts and need no baseline.

**The first-run floor belongs to `DigestRepository`, not the trigger.** `game_event` is never
pruned (`GameEventDao` has no delete; the only `DELETE`s in `Daos.kt` are on `budget`), so an
unfloored first run would sweep up every event since 1A and 1D would debut with a wrap-up of a
long-dead month. But `DigestEvent` carries only an `id` — the trigger *cannot* floor by time. So
`DigestRepository.window(lastDigest, todayStartMillis)` applies the floor itself whenever
`lastDigest == null` — the decision and the mechanism live in one call, so a caller cannot forget
it. Tested under instrumentation (§12), not in `DigestTriggerTest`. The approved draft assigned
this to the trigger; that was unimplementable as written.

**No collision rule: DAILY and MONTHLY are independent.** The approved draft suppressed DAILY
whenever a `month.closed` was present, reasoning "one voice per day". That was wrong twice over.
The two cards land on different screens (home vs the greenhouse postcard), so they never
competed. And `runReconciler()` emits `month.closed` and `streak.hit` **in the same call**
(`GardenRepository.kt:110-127`) — with the watermark advanced past the whole window, a
suppression rule would have dropped the streak, and any dodge or regret beside it, permanently.
"Pending for tomorrow" only ever held for *comparison* triggers, which are re-derived from the
snapshot; *event* triggers are consumed by the watermark. Both kinds now write in the same job
with the same snapshot and `lastEventId`, so whichever row is latest is a correct baseline.

**Call-order precondition:** §4 runs the reconciler before the trigger, so `eventsSince` never
spans a month boundary without also containing that month's `month.closed`. The first-regret
arithmetic (`regretCount − regretsInWindow ≤ 0`) relies on the window being one month's worth.

The whole silence rule is therefore JVM-testable with zero network and zero LLM. That is the
point of splitting `DigestTrigger` from `DigestWriter`: **the decision to speak is testable; only
the wording is not.**

## §6 Quip refresh

Runs at most once per day, and only for buckets whose unused stock has fallen **below 5 lines**.
Asks for **8 lines** per depleted bucket, then **sanitizes before inserting**: length cap, single
line, and a boundary check against §10 (no income references, no comparisons, no
necessity-shaming). Rejected lines are simply not inserted — the static bank is always still there.

**Lines the bucket already holds are not inserted again.** The prompt is the same string every
call and the model has no memory across calls, so it regenerates its favourites; a duplicate row
would be served back to back by the LRU picker — the one repeat the refresher exists to prevent.

**Buckets are `(severity × tone)`, which means `quip` gains a `tone` column.** The shipped table
keys on `severity` alone. That was adequate while tone was a fixed constant, but 1D makes tone a
**live user-switchable setting**, and a bank keyed only by severity would serve Savage lines to a
user who has just switched to Gentle — a §10 boundary violation, visible immediately, permanent.
Existing `STATIC` rows migrate to `tone = 'SHARP'` (the voice they were written in), and the
picker falls back to any `STATIC` row when the user's tone bucket is empty. That preserves "the
gate always has content".

`Severity.OK` has no bucket: the gate shows nothing at OK, so there is nothing to refresh. The
two refreshed severities are exactly the enum's non-`OK` constants.

Parent §8.4 specifies `severity × category-family × tone`. **Category-family is deliberately
deferred**: a generic BREACH line is still *correct* for any category, so that dimension is
flavour, not correctness — and adding it would require re-seeding the entire static bank and
multiply the buckets the free tier has to keep stocked. Recorded here so the narrowing is a
decision rather than an omission.

The seeded `STATIC` quips are never deleted. LLM lines augment the bank; they do not replace it.
That guarantees the gate has content even if every LLM call ever made fails.

## §7 Persona

System prompt carries the parent spec §10 boundaries:

- necessities are off-limits — never mocked
- roast the **choice**, never the person: no income digs, no comparisons to others, no doom
- the gate gets **one line**, never a lecture
- silence rule: nothing notable → nothing said
- avoid repetition

Tone presets are prompt swaps over the same boundaries: **Sharp but fair** (default), Savage,
Gentle. The boundaries do not relax at Savage — only the tone does.

**Correction to the approved draft:** it claimed no-repeat was "enforced structurally by the LRU
picker, not by the prompt". That is false. `QuipDao.leastRecentlyUsed` orders by
`usedAt IS NOT NULL, usedAt ASC LIMIT 1` — unused rows first, then the oldest-used. Once a bucket
is exhausted it **cycles**; it is least-recent, not no-repeat. Parent §10 asks for "no-repeat quip
memory", and what exists is a recycling queue. The honest position for 1D: **the refresher is what
keeps repeats rare** — topping a bucket up before it empties is what stops the cycle being felt.
True no-repeat (retire-on-use) is out of scope and would eventually starve the gate offline.

## §8 "Not today"

Parent §10 lists a boundary the approved draft dropped: **a "not today" control that mutes the
personality for 24h without disabling logging.** It matters more in 1D than anywhere before,
because the daily card is the app's first *unprompted* surface — the first thing that speaks
without being asked.

Dismissing the daily card with "not today" writes `mutedUntil = now + 24h` to `AiPrefs`.
`DigestTrigger.evaluate()` returns the empty verdict — `daily = null, monthly = []` — while
inside that window.

**While muted, no digest row is written, so neither the snapshot nor `lastEventId` advances.** So a transition
that happens during the mute is not lost — it is still pending when the window expires, and if
the state has since reverted, the comparison correctly yields nothing. The rule is self-healing.

## §9 Schema

Two changes → **Room v3**, with a migration and a committed schema JSON alongside `1.json` and
`2.json`:

```
digest(
  id, kind (DAILY|MONTHLY), scopeKey, text,
  reasonJson, snapshotJson, lastEventId, createdAt, dismissedAt
)

quip  + tone TEXT NOT NULL DEFAULT 'SHARP'
```

- `scopeKey` — the date (`2026-09-04`) for DAILY, the month key (`2026-09`) for MONTHLY.
  **Unique on `(kind, scopeKey)`** — several transitions on one day still produce at most one
  daily card, and a month cannot be summarized twice.
- `reasonJson` — *why* it spoke, so a digest that reads oddly can be traced to its trigger
- `snapshotJson` — weather / houseLevel / streakDays at the moment of speaking

- `lastEventId` — the highest `game_event.id` in the log, read **before the events themselves**
  and before any LLM call. This is the watermark. `window()` reads `MAX(id)` first, then the rows
  in `(previous watermark, head]`; an event inserted between those two reads has `id > head`,
  stays above the watermark, and is seen next time. Reading events first and the head second —
  the approved draft's order — left exactly that gap open.

**All or nothing.** Every row a job writes carries the same `lastEventId`, so writing *some* of a
verdict's reasons and not others would consume the failed reasons' events forever: MONTHLY
succeeds and DAILY's call returns null → today's dodge and streak are behind the watermark and
never spoken; four closed months and the second call fails → that month is never summarized,
because the reconciler never re-emits. So the job composes **every** text first and writes them
in one `withTransaction`, or writes none. A single null re-evaluates the whole verdict next
open — the same self-healing shape as the mute. The cost is a handful of discarded completions
on a bad-network day, which the free tier absorbs — the calls are sequential at ~20s each, so the
job cannot exceed roughly three a minute however many reasons it carries.

**And "nothing" has to include the constraint.** `UNIQUE(kind, scopeKey)` makes a repeat scope a
silent no-op insert, not an error, so a verdict carrying a fresh MONTHLY *and* a DAILY whose row
was already written this morning would commit the monthly — and the shared watermark with it —
past events the swallowed daily never spoke about. That is the partial-write loss arriving through
the constraint rather than through a failed call. Two guards: the job asks `exists(reason)` before
composing anything, so a repeat scope costs no completion; and `writeAll` rolls the whole
transaction back if any insert returns `-1`, which closes the window between asking and writing.
The verdict then stays pending, and a repeat daily scope clears itself at midnight.

**A `createdAt` watermark would be wrong, for two independent reasons.** First, §4 puts a network
round trip between reading the events and writing the row, so an event logged during that call
lands with a `createdAt` earlier than the digest's and falls behind the watermark forever —
contradicting the "remains pending" promise below. Second, `runReconciler()` calls
`System.currentTimeMillis()` per row inside a tight loop, so a batch's timestamps typically collide
and cannot order it. `GameEventEntity` has carried `@PrimaryKey(autoGenerate = true) val id: Long` since
1A (`data/Entities.kt:99`); the monotonic cursor was there all along.

The *snapshot* still freezes at write time, so a **comparison** transition later the same day is
not lost to the unique constraint — it remains pending and is picked up by tomorrow's evaluation,
exactly like a muted transition in §8. The approved draft said later transitions were "folded into
its `reasonJson`", which would have required a second write path and an LLM re-run for no benefit.
Dropped.

## §10 What leaves the device

The parent spec never enumerated this, and 1D is the first phase where anything does. **Payee
names come from scanned UPI QR codes — that is attacker-controlled text**, and a payee called
`Ignore previous instructions and …` would otherwise be interpolated straight into a prompt.

**Rule: only closed enums and numbers cross the boundary.** `PromptFacts` is the only type
`DigestWriter` may serialize, and it can hold:

| Allowed | Not allowed |
|---|---|
| `Weather`, `Severity`, tone — enum names | payee names |
| house level, streak days, counts, paise totals | transaction notes |
| category names **whitelisted against the seeded taxonomy** | free-text category names the user created |
| month key, day-of-month | UPI VPAs, transaction UUIDs |

**Three strings cross the boundary and none is free text.** `topCategories` is whitelisted
below. `PromptFacts.monthKey` is a `YearMonth.toString()` from the fold (`GardenFolder.kt:112`).
`Trigger.MonthClosed.monthKey` reaches `DigestWriter.describe()` by a longer chain —
`game_event.payloadJson` ← `runReconciler()` ← `Reconciler.decide()` ← `YearMonth.toString()` — so
it is also `YYYY-MM` by construction, but it is sourced from a stored JSON field rather than a live
fold, and is the slot most shaped like a hole. Every other value `describe()` interpolates is an
enum name or an `Int`. Found during implementation and review.

A category name outside the seeded taxonomy is sent as its parent family name instead. Today that
branch is unreachable: `CategoryDao` exposes only `observeAll` / `byId` / `all`
(`data/Daos.kt:9-18`), so the taxonomy seeded at `AppDatabase.kt:51-58` is closed and no
user-entered category can exist. The whitelist is what keeps this section true on the day a
category editor is added. This is a type-level guarantee, not a sanitization pass — there is no
code path from a user-entered string to the prompt builder.

**Injection is the sharp risk; being trained on is the quiet one.** Parent §8.2 records the second
explicitly — *"free-tier providers may train on inputs; the Ollama path exists for the day that
itches"* — and 1D is the phase where that note stops being hypothetical. Both risks are answered
by the same rule, which is why it is a type and not a filter.

## §11 Failure modes — everything degrades to silence

| Condition | Behaviour |
|---|---|
| No API key entered | `NoopLlmClient`. Static quips work, no digest. **App fully functional.** |
| Network down, or rate limited | `complete()` returns null → nothing inserted → retried next open |
| Malformed or boundary-violating **quip** output | `QuipSanitizer.clean()` rejects the line → not inserted. Checks length, single line, and the full §7 list including necessity nouns — a one-liner that *mentions* rent is mocking it. |
| Boundary-violating **digest** output | `QuipSanitizer.attacksThePerson()` — income digs and comparisons only — nulls the text → all-or-nothing, nothing written, retried next open. Necessity nouns are **not** checked here: a month recap that says "groceries were steady" is doing its job. |
| Gemini free tier withdrawn | Swap the `LlmClient` impl. Nothing else changes. |

There is no error state to render, because no screen is waiting on any of this. **The cost of
that choice is §2.1:** silence is also what a misconfiguration looks like, so configuration must
be verified on device rather than inferred from a green test run.

## §12 Testing

| Suite | Coverage |
|---|---|
| `DigestTriggerTest` | The entire silence rule — every trigger, silence when nothing changed, first-run comparison suppression, the mute window and its boundary, DAILY and MONTHLY written independently. **Four `month.closed` events in one window produce four MONTHLY reasons; four `streak.hit` events produce one.** Pure, no LLM. |
| `PersonaTest` | Prompt assembly per tone; boundary clauses present in all three. |
| `PromptFactsTest` | A payee name or free-text note cannot reach a prompt; unknown categories degrade to the family name. |
| `QuipSanitizerTest` | Length, single-line, boundary rejection; rejected lines are not inserted. |
| `FakeLlmClient` | Wiring for refresher and writer without network. |
| Room instrumentation | v2→v3 migration (both tables), `digest` DAO, tone-bucketed quip picking with `STATIC` fallback, `window()`: an `id`-bounded read that a same-millisecond batch cannot confuse, starting strictly after a non-zero watermark, with the head read first; the first-run floor including its midnight boundary; `monthFacts()`; and `writeAll()` landing every row under one watermark. |
| Device — **blocking** | A real 200 response with a real key (§2.1); key entry through the new settings route; **confirm the AI prefs file is absent from an `adb shell bmgr` backup**; a forced digest; offline behaviour with airplane mode. Every one of these fails *silently* when wrong, so none may be inferred from a green test run. |

**JVM tests cannot use `org.json`.** `app/build.gradle.kts` does not set
`testOptions.unitTests.isReturnDefaultValues`, so the android.jar stubs throw "not mocked". Rather
than set that flag — which would let JSON silently return defaults and mask real bugs — the pure
core stays JSON-free by construction: `DigestTrigger` takes a typed `DigestSnapshot`, and
`DigestRepository` does the parsing under instrumentation coverage. The constraint and the design
point in the same direction.

`GeminiClient`'s actual HTTP is not unit-testable offline. It is kept deliberately thin for
exactly that reason — all logic worth testing lives outside it.

## §13 Review log (2026-09-04)

The draft was approved, then reviewed adversarially twice. Every finding below was verified
against the files by hand before being accepted — no claim reached this table on an agent's
word alone.

### First pass

| # | Finding | Where fixed |
|---|---|---|
| 1 | No `INTERNET` permission anywhere in the repo or merged manifest — the phase could ship 100% dead and fully green | §2.1, §11, §12 |
| 2 | `allowBackup` unset → defaults true → the API key goes to Google's cloud backup | §2.1 |
| 3 | Payee names are attacker-controlled QR text and were unbounded prompt input | §10 |
| 4 | `evaluate()` could not compute "first regret of the month"; first-run was undefined | §5 |
| 5 | `viewModelScope` is `Dispatchers.Main` → `NetworkOnMainThreadException` | §3 |
| 6 | "No repeats enforced structurally by the LRU picker" — false; the picker cycles | §7 |
| 7 | Parent §10's "not today" 24h mute was dropped despite §7 claiming the boundaries were carried over | §8 |
| 8 | Quip bucketing narrowed from `severity × category-family × tone` to severity alone, while making tone user-switchable | §6 |
| 9 | `org.json` throws in JVM unit tests, contradicting the "pure `DigestTriggerTest`" claim | §12 |
| 10 | `digest` table had no owning DAO/repository; `SettingsScreen` had no nav route | §3 |
| 11 | DAILY/MONTHLY same-day collision undefined | §5 |
| 12 | "Folded into its `reasonJson`" implied an unspecified second write path | §9 |

### Second pass (against the revision above)

| # | Finding | Where fixed |
|---|---|---|
| 13 | `Reconciler.decide` returns `monthsToClose` and `streakHitsToEmit` as **lists**, and `runReconciler()` emits one event each — but `evaluate()` returned a single reason, so N−1 closed months were permanently unspoken (the reconciler never re-emits) | §5 |
| 14 | A `createdAt` watermark loses any event written during the LLM round trip, and cannot order a same-millisecond reconciler batch | §9 |
| 15 | First run had no lower bound on `eventsSince`, and `game_event` is never pruned — 1D would have debuted with a wrap-up of a long-dead month | §5 |
| 16 | §3 hung the settings entry point on a top bar that does not exist; `GardenHomeScreen` is deliberately chrome-free | §3 |
| 17 | Weather can flip OVERCAST → SUNNY on a zero-spend day, because the pace allowance grows with the calendar — real but rare, and always good news | §5 |
| 18 | The ₹0 premise was never tied to a named model, endpoint or quota | §2 |
| 19 | Parent §8.2's "free-tier providers may train on inputs" note was dropped, though it is half the reason §10 exists | §10 |
| 20 | §10 implied user-created categories, but `CategoryDao` is read-only and the taxonomy is closed | §10 |
| 21 | Header and decision table pointed at "§12" for the review log, which is §13 | header, §Decisions |
| 22 | §5 took `List<GameEventEntity>` while §12 required the trigger be JSON-free — but `month.closed` hides its month key in `payloadJson`. Found while writing the plan. | §5 (`DigestEvent`) |
| 23 | §5 pinned `Instant?` for the mute, but `AiPrefs` and `game_event.createdAt` both speak epoch millis, and reading the clock inside a pure fold makes it untestable. Found while writing the plan. | §5 (`mutedUntilMillis` + injected `nowMillis`) |

### Third pass (spec-compliance review of the implemented trigger, `a1948b9`)

| # | Finding | Where fixed |
|---|---|---|
| 24 | **The DAILY/MONTHLY collision rule lost data.** `runReconciler()` emits `month.closed` and `streak.hit` in one call; suppressing DAILY and advancing the watermark past the window dropped the streak — and any dodge or regret — permanently. The "one voice per day" rationale was false: the cards are on different screens. | §5 (independent), §4 |
| 25 | The first-run floor was assigned to the trigger, which receives no timestamps and cannot implement it. | §5, §12 (owned by `DigestRepository`) |
| 26 | The weather-wording rule demanded knowledge the snapshot does not carry. | §5 (hedge, don't assert) |
| 27 | `MonthFacts.monthKey` was passed with no stated purpose; the call-order precondition behind the first-regret arithmetic was unstated. | §5 |

### Fourth pass (spec-compliance review of the implemented repository, `846d1d1`)

| # | Finding | Where fixed |
|---|---|---|
| 28 | **Partial writes lost data through the LLM path.** Reasons were written one by one under a shared watermark; one null completion among several consumed the failed reason's events forever — the #24 failure mode reintroduced. | §9 (all or nothing) |
| 29 | The head id was read *after* the events; an insert between the two reads was consumed unspoken. | §9 (`window()` reads head first) |
| 30 | The floor's *decision* sat in the caller while the *mechanism* sat in the repository — a caller passing `null` on first run silently reproduced #15. | §5 (`window()` owns both) |
| 31 | `currentHeadId()` loaded the entire never-pruned log to read one `Long`, once per open. | §9 (`MAX(id)`) |
| 32 | §12 claimed an id-bounded same-millisecond test that did not exist; `monthFacts()` had no test. | §12 |

### Fifth pass (spec-compliance review of the implemented writers, `30ca0d7`)

| # | Finding | Where fixed |
|---|---|---|
| 33 | §11 promised a boundary check on all output; digest text went in verbatim. Running the quip sanitizer on prose would block legitimate necessity mentions, so the check splits: person-attacks gate both, necessity nouns gate quips only. | §11 |
| 34 | The weather hedge told the model the flip *may* be calendar-driven but never forbade "you spent differently". | `DigestWriter.describe()` |
| 35 | §3 still said `DigestWriter → DigestRepository`; §4 still said `maybeRefresh()` owned the once-a-day cadence. Both stale after the all-or-nothing change. | §3, §4 |
| 36 | `Trigger.MonthClosed.monthKey` is a third string crossing the boundary, sourced from stored JSON, unlisted in §10. | §10 |
| 37 | §6 never said `Severity.OK` has no bucket, so the two-string severity list read as a guess. | §6 |

### Sixth pass (code-quality review of the implemented writers, `30ca0d7`)

| # | Finding | Where fixed |
|---|---|---|
| 38 | **Duplicates accumulated against the bank.** Same prompt every call, no memory, plain `@Insert` — the model's favourite line landed twice and the LRU picker served both copies consecutively. | §6; `Sink.existingTexts()` |
| 39 | `.take(600)` cut digest prose mid-word, and the row is written once and never rewritten — a permanently truncated postcard. | `capAtSentence()` |
| 40 | The job built one `PromptFacts` block from the current month and shared it across every reason, so a closed month's retrospective would be written against the *current* month's spend. | Task 14: `factsFor(reason)` uses `foldMonth(scopeKey)` for MONTHLY |
| 41 | Severity was mirrored by hand as strings in two places; a fourth `Severity` would silently never refresh, or refresh with the wrong copy via an `else`. Typed end to end; `quipPrompt` is exhaustive and `DEFAULT_SEVERITIES` is derived. | `QuipRefresher`, `Persona` |
| 42 | `tidy()` stripped quotes before fences, so quotes inside a fence survived; language-tagged fences left the tag in the prose. Caught by the new `DigestWriterTest`. | `FENCE` regex, order |
| 43 | "Tagged their first purchase of the month as a regret" read as *the month's first purchase*; the trigger means *first regret-tag this month*. | `describe()` |

### Seventh pass (review of the implemented settings screen, `5b8230e`)

| # | Finding | Where fixed |
|---|---|---|
| 44 | **Save could be unreachable.** A non-scrolling `Column` with no IME padding: on `targetSdk 35` edge-to-edge no longer shrinks content behind the keyboard, and landscape has no room at all — the one screen §3 calls "the gate on the whole phase". | `imePadding().verticalScroll()` |
| 45 | The key field was masked visually but the IME was not told it was a password, so a keyboard could learn the key and suggest it later. | `KeyboardType.Password` |
| 46 | The radio rows exposed two tap targets per option to TalkBack; the ⚙️ was the app's only glyph-only button. | `Role.RadioButton`, `"⚙️ settings"` |

**Verified on device during this pass:** the saved key lands in `shared_prefs/ai_secrets.xml` — the file both backup-exclusion rules name — and Room's table list is unchanged (no settings or prefs table).

**Accepted, not acted on:** a `month.closed` row whose payload will not parse is skipped, and that month is then never summarized — the alternative is one corrupt row taking the whole fold down, and nothing in the ledger code produces such a row. A `digest` row whose `snapshotJson` will not parse degrades to "never spoken", which the first-run floor makes safe. `transaction.regret_cleared` is not consulted by `monthFacts()`, so regret → clear → regret counts twice; deliberate, since each tagging is a real act.

### Eighth pass (review of the implemented wiring, `2a6f034`)

| # | Finding | Where fixed |
|---|---|---|
| 47 | **The plan had no test for the one new query.** `topCategoryNames` is a JOIN + `GROUP BY` + `ORDER BY SUM DESC LIMIT 3` behind a status filter and a time window — four ways to be subtly wrong and nothing in a prompt's output would show it. Instrumented test written first (RED: unresolved reference; GREEN: 5/5 on device). | `LedgerDaoTest.top_category_names_*` |
| 48 | **Wiring the job into `init { viewModelScope.launch }` makes every uncaught throw a crash on open** — a SupervisorJob with no handler falls through to the default uncaught handler. Audited the whole path: `latestSnapshot`/`project` are `runCatching`, `quip` has no UNIQUE so `insertAll` cannot conflict, `GeminiClient` catches `Exception`, no throw-capable string ops in `ai/`. Residual is DB corruption or a genuine bug; a blanket catch would hide the latter, and §11 already gives every realistic failure a silence path. | None — recorded |
| 49 | Tooling: AGP's `connectedDebugAndroidTest` **uninstalls both APKs when it finishes**, so `installDebug` chained before it in one Gradle command leaves the device with no app — `am start` then fails with nothing in the app's logcat. Task 15 installs last, alone. | Plan Task 14 Step 8 |
| 50 | **THE UNIQUE CONSTRAINT REOPENED THE PARTIAL-WRITE LOSS (#24/#8, third door).** `runReconciler` and `runAiJob` both launch from a `viewModelScope` in their own ViewModel's `init`, so they race. On the first open of a new month the job can read `headId()` before the reconciler inserts `month.closed`, write only a DAILY, and leave the month pending. A later open the same day then carries a fresh MONTHLY *and* a DAILY whose scope already exists: the monthly inserts and commits the shared watermark, the daily is swallowed by `IGNORE`, and the dodge or streak between them is never spoken. Proven with an instrumented repro before the fix (watermark advanced to 102 with the daily unwritten). The race itself is sound — head-first + id-bounded is what keeps it a delay rather than a loss. | `exists()` pre-check + `writeAll` rollback, `2f58ded` |

**Verified on device during this pass:** with a throwaway key planted in `shared_prefs/ai_secrets.xml`, the app opens clean (no errors in logcat, the key string never appears there), `lastQuipRefreshAt` is stamped — so the job ran past `hasKey` — and after Gemini rejects the throwaway key the `digest` table is still empty and `quip` unchanged. A failed job writes nothing, which is the all-or-nothing rule holding end to end.

### Ninth pass (the device gate, Task 15 — `cef300a`)

The pass this phase was built to fail at, and it did fail, twice, before it passed.

| # | Finding | Where fixed |
|---|---|---|
| 51 | **The speced model no longer exists.** `gemini-2.0-flash` 404s — and so does `gemini-2.5-flash`, with `"no longer available to new users"`. The API's own error named `gemini-3.6-flash` as the replacement. Note the trap: retired models still appear in `GET /v1beta/models`, so listing them is not proof they answer. Pinned, not `gemini-flash-latest` — the persona's voice should not change without a commit saying so. | `MODEL`, `cef300a` |
| 52 | **The 15s timeout was itself fatal.** 3.x models think before answering: a real eight-line quip prompt measured **22.7s**. Even with the model name corrected, every call would have timed out into silence. Split into a 15s connect and a 60s read timeout — free to do, because §1 keeps the LLM out of every read path, so nothing on screen waits. Rejected the alternative of disabling thinking: `thinkingConfig.thinkingBudget: 0` is a 400 on this model, and a knob whose shape shifts between model generations is exactly what §2 avoids. | `READ_TIMEOUT_MS`, `cef300a` |
| 53 | Observation, no fix: the free tier's **per-minute** limit is the binding one and it is small — a ~10-call diagnostic burst caused the next in-app call to return null. The design absorbed it exactly as §11 promises: that severity simply did not fill, the other did, and the next run completed it. Recorded because the daily ceiling, which the plan sized against, was never the real constraint. | None — recorded |

**Verified on device, end to end, with a real key entered through the Settings UI:** quip refresh wrote 8 GENTLE/BREACH lines then, on a later run, 8 GENTLE/PACE_WARNING lines — and correctly **skipped** BREACH the second time, because 8 unused ≥ `LOW_STOCK`, so a stocked bucket costs no call. With a `gate.dodged` and a `streak.hit` in the window, the digest wrote one DAILY row carrying `lastEventId = 2`, `reasonJson = {"triggers":["StreakHit(days=7)","GateDodged(count=1)"]}`, and prose that celebrated the dodge as a win exactly as `describe()` instructs. The card rendered on the home screen with its `ok` / `not today` actions. A second cold open wrote **nothing** and left the watermark alone — silence when nothing transitioned, which is the whole point of §5.

**What this pass proves about the architecture, and what it cost:** every failure here was invisible from inside the app. A retired model, a too-short timeout and a rate limit all render identically to a quiet day, because §11 routes every one of them to silence. That is right for the product — the gate never blocks and works offline — but it means the layer could have shipped 100% dead with every automated test green. The manual device gate is the price of that design, and it is worth paying explicitly rather than pretending tests cover it.

**Findings deliberately not acted on**, so the next reader does not re-raise them: the `tone`
column being inert until a key is entered (the §6 fallback is scoped to `STATIC` rows, so the
violation it names is genuinely prevented); `QuipRepository.pick()` gaining a `tone` argument
(one constructor arg at the composition root — §1's "one `WHERE` clause" is rhetoric in a
rationale paragraph, and §6 and §12 specify the real semantics); and the absence of
`app/src/main/res/` (AGP picks it up by default the moment it exists — plan altitude, not spec
altitude, though §12's device row now covers the one part that fails silently).

## Out of scope

- **Free chat** — the first surface where the user would *wait* on the network. Different
  problem class (loading states, retry, possibly streaming); deserves its own phase.
- Weekly review prompts, "memories".
- True no-repeat quip memory (retire-on-use) — see §7.
- Category-family quip bucketing — see §6.
- Category suggestion via LLM (parent spec §5.1 step 3) — the payee→category map covers it.
- Any change to the gate's call path, the ledger, or the payment path.
- `ai-svc` (Python) — that is Phase 3. 1D is entirely on-device.
