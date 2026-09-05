# Phase 4A — Collections & Rare Species: Design

**Status:** approved in conversation 2026-09-06. §4B (landmarks) is deliberately deferred.

**Goal:** Give the garden a reward ladder that pulls in the same direction as the product —
you collect by spending *less*, not more.

**Parent spec:** `docs/superpowers/specs/2026-07-03-expense-garden-design.md` §13 (Phase 4:
"gamification depth — collections, rare species, redemption polish").

---

## 1. The constraint that shapes everything

Every collection mechanic in the genre rewards **volume**: more pulls, more chances, more
drops. In an expense tracker that is an anti-goal wearing a fun hat. "Spend more to complete
your collection" contradicts the product's entire purpose and violates the standing invariant
that loss-aversion pressure lives at the gate and nowhere else.

So the design constraint is not *what* to collect but **finding a rarity axis that cannot be
farmed by spending**. Anything keyed to transaction count or amount fails immediately.

Three ingredients, arranged so none of them pull against the product:

- **Restraint earns.** Streaks, dodges, months closed under budget, no-spend days.
- **Variety earns.** Breadth of categories in a month.
- **Chance reveals.** Which rare you get, never *whether* you get one.

That last split is the whole trick. Keyed per-transaction, a roll makes spending the optimal
collector strategy. Keyed to "which of the eligible pool did I just earn", it preserves the
entire surprise with nothing farmable.

## 2. The tier ladder

The three candidate *forms* a rare could take are not three parallel systems — they are the
rarity tiers. This is what lets the island read as a record of your best behaviour, the same
job the house ladder already does for months tracked.

| Tier | Form | Feels like |
|---|---|---|
| **Uncommon** | A new *variant* of a species you already grow | "my tulip came up golden" |
| **Rare** | A dramatic *form* of a species you grow — lotus, bonsai, topiary | "what is that" |
| **Landmark** | An *island feature* — pond, beehive, stone lantern | "the island changed" |

Escalating art cost lines up with escalating rarity: roughly 10 variants, 4 species, 2
landmarks — not 10 of each.

## 3. Triggers

Every signal below already exists in `game_event` or falls out of the existing fold. **No new
tracking is required for 4A.**

| Tier | Trigger | Source |
|---|---|---|
| Uncommon | `streak.hit` at **7** | already emitted by `Reconciler` |
| Uncommon | 3 × `gate.dodged` within one month | already emitted |
| Uncommon | a **7-day consecutive no-spend run** in a month | derived in `GardenFolder` |
| Uncommon | `transaction.regret_cleared` | already emitted — see §3.2 |
| Rare | `month.closed` where `spentPaise <= overallBudgetPaise` | payload already carries both |
| Rare | `streak.hit` at **30** | already emitted |
| Rare | spend in ≥ 8 distinct root categories in a month | derivable from the ledger fold |
| Landmark | house level reaches **3** (6 months tracked) | `GardenFolder.houseLevel` |
| Landmark | house level reaches **4** (12 months tracked) | `GardenFolder.houseLevel` |

### 3.1 Two corrections made during design, recorded because they were nearly wrong

**Streaks are within-month, not lifetime.** `StreakMath.underPaceStreak` walks days
`1..today-1` of the *current* month and resets at every rollover;
`Reconciler.STREAK_THRESHOLDS` is `[3, 7, 14, 30]`. A "90-day streak" landmark — the obvious
first guess — could never have fired. Landmarks therefore key off months tracked, which is
genuinely long-horizon and already computed.

**`month.closed` already carries what the Rare tier needs.** Its payload is
`{month, spentPaise, overallBudgetPaise}`, so "closed under budget" is a pure function of the
event. No new event type, no schema change.

**No-spend must be CONSECUTIVE, and this spec originally got it wrong.** The first draft used
`StreakMath.noSpendDays`, which returns a *total* count, and dismissed run-length math as "a
distinction nobody will feel". Implementation proved otherwise within minutes: nobody spends
every day, so a single purchase in a twenty-day stretch already yields nineteen no-spend days.
The trigger fired for essentially every month — it was measuring arithmetic, not restraint, and
it broke three pre-existing fold tests by handing out rares unprompted. A seven-day *unbroken*
run is a real thing you had to do, so `GardenFolder` computes the longest run instead.

### 3.2 One trigger deliberately excluded

**"A month with zero regrets tagged" must never be a trigger.** It is the obvious candidate
and it is poison: it rewards *not tagging*, which corrupts the ledger and directly violates
*never punish the log*. Honesty must cost nothing.

The inverse is used instead. `transaction.regret_cleared` — re-tagging a regret as worth-it —
earns an Uncommon. Redeeming is rewarded; tagging honestly in the first place is free. This is
the parent spec's "redemption polish" made concrete, and it closes the loop on the standing
invariant that *every bad state has a redemption path*.

### 3.3 Idempotence — every trigger fires at most once per scope

Without this, several triggers are farmable and §1's entire premise collapses. Each trigger
carries a **scope key**, and the fold grants at most one earn per `(trigger, scopeKey)` across
all of history:

| Trigger | Scope key | Why |
|---|---|---|
| `streak.hit` 7 / 30 | `"streak7:<month>"` / `"streak30:<month>"` | `Reconciler` already dedupes per month, but the fold must not double-count a replay |
| 3 gate dodges | `"dodges:<month>"` | otherwise a 4th, 5th, 6th dodge each earn again |
| 7 no-spend days | `"nospend:<month>"` | same |
| Month under budget | `"under:<month>"` | naturally once, keyed for safety |
| ≥ 8 root categories | `"breadth:<month>"` | otherwise every further category earns again |
| `transaction.regret_cleared` | **`"redeem:<txnUuid>"`** | see below |
| House level 3 / 4 | `"house:3"` / `"house:4"` | lifetime, once ever |

**The redemption trigger is the dangerous one.** `LedgerRepository.setRegret` no-ops only when
the value is *unchanged*, so REGRET → WORTH_IT → REGRET → WORTH_IT emits `transaction.regret_cleared`
on every other toggle. Two taps, repeated, would farm Uncommons without limit — in the one
design whose whole premise is that nothing is farmable.

Scoping it to the transaction uuid closes it: a given purchase can be redeemed for a reward
exactly once, however many times it is re-tagged afterwards. The fold sees every event, so
this is a `distinctBy` on the uuid, not new state.

This is also why the earn is computed **in the fold** rather than emitted by the reconciler.
A fold that sees all of history can enforce once-ever; an emitter that sees only the current
window cannot.

## 4. How a rare reaches the island

There is a strict 1:1 today: **every plant is exactly one real transaction.**
`PlantMapper.map()` takes a `TransactionEntity` and returns one `MappedPlant`. That property
is why the garden is trustworthy — nothing on the island is decorative.

Rewarding restraint collides with it head-on: a no-spend week has no transaction to attach to.

**Resolution: bank the seed.** An earn is *derived* — the fold detects it from events already
in the log (§3.3) and pairs it with the **next qualifying transaction**, which grows as that
rare instead of its normal form.

**And the rare must match that purchase's own species.** A second draft let an earn carry its
species independently, so a Groceries purchase could render as a Golden Tulip. That is a subtler
version of the same lie: the plant is still a real transaction, but it now misreports the
category, and "the garden is your spending" stops being true in the way that matters. Every
plantable rare therefore names a `baseArchetype`, and a seed is only ever spent on a purchase
that already grows that species — otherwise it waits. A rare is the plant you would have grown
anyway, grown better; it decorates, it never re-labels.

This also removes the need for any renderer change: `SpritePainter` already loads
`<archetype>_<variant>.png`, so a rare is a further variant of a species that already exists. The invariant survives untouched,
`PlantMapper`'s signature does not change, and it reads better than any token grant: a week of
restraint makes the next thing you buy grow into something better.

"Qualifying" excludes investments (they are back-row trees, not bed plants), weeds and
zombies — a rare seed is never consumed by a breach purchase or a regret. It waits.

### 4.1 The roll must be deterministic

**The garden is a pure fold, so the chance element cannot be a runtime roll.** If "which
species" were decided by `Math.random()` at reveal time, replaying the log would produce a
different island on every fold and the greenhouse's archived months would drift — the same
defect class as a wall-clock watermark, which this project has now been bitten by twice.

The species is therefore derived: `abs(hash(earnedEventId)) % pool.size`. Still a surprise to
the user, still a pure function of the log, still identical on every replay.

### 4.2 Nothing is stored — not a table, not even an event

Both the earn and the pairing are derived. The fold detects earns from the events already in
the log, orders them, and pairs the Nth earn with the Nth qualifying transaction that follows
it chronologically. Unpaired earns stay pending; unpaired transactions grow normally.

**No new table and no new event type.** An earlier draft of this spec had the reconciler emit
a `rare.earned` event, which contradicted §3.3 — an emitter sees only the current window and
therefore cannot enforce once-ever, while a fold over all of history can. Deriving is also
what keeps the collection immune to a replay granting the same rare twice.

The cost is that earn detection runs over the full event log on every fold rather than
incrementally. At one user's lifetime volume — a few thousand events — that is nothing, and
`GardenFolder.foldAllTime` already walks every transaction ever on each collection.

## 5. The album

The greenhouse gains a **Collection** section. It already exists as the "look back at what you
have grown" surface, so it inherits the framing and costs one screen's work rather than a new
nav destination.

- **Earned:** the sprite, rendered, with when and how it was earned.
- **Unearned:** a silhouette with its condition stated — "close a month under budget".

Silhouettes are what make a collection pull rather than merely record, and they double as
documentation: otherwise you would never learn a lotus exists. The nag risk is acceptable here
precisely because every condition is a behaviour the app already wants — *spend less, tag
honestly, keep tracking*. There is no condition a user could satisfy by spending more.

## 6. Consistency rules

- **A rare that is later tagged as a regret still becomes a zombie.** No exemptions. The
  honesty of the garden outranks the prettiness of the collection.
- **A rare seed is not consumed by a weed or zombie purchase** (§4). It waits for a clean one.
- **Rares are permanent.** Once grown, the plant is a normal plant with a special sprite; it
  archives into its month's greenhouse card like anything else.
- **Necessities can carry rares.** Groceries growing a rare is fine — necessities are never
  shamed, and excluding them would quietly imply they are lesser.

## 7. Art

Roughly 10 variant sprites and 4 species, via the existing pipeline in `tools/art/`.

Verified present on 2026-09-06: the quantized model at `~/.cache/mflux-models/flux1-schnell-q4`
(9 GB) and the venv at `~/.cache/expense-garden-art-venv` (1.2 GB, `mflux-generate` on its
path). This was a five-approach blocker during 1C.6; it is a solved problem now, and that is
what makes new art affordable for this phase.

Naming follows the existing convention — `SpritePainter` loads
`assets/garden/<archetype>_<variant>.png` — so variants need no renderer change at all.

## 8. Scope

**4A (this spec):** the earning engine, the pairing fold, Uncommon variants, Rare species, and
the greenhouse Collection section.

**4B (deferred):** Landmarks. A pond is not a grid cell, and `SpiralTiler` has no concept of
placing anything outside its tiling. That is real geometry work and it should not hold up the
rest. The Landmark *triggers* are specified here so the earning engine is built once, but
earned landmarks simply record into the album until 4B renders them.

### 8.1 Out of scope

- Trading, sharing, social features. One user.
- Streaks that span months. Would require reworking `StreakMath`'s month-scoped model —
  a bigger change than this phase justifies.
- Any trigger keyed to transaction count or amount (§1).
- Retroactive awards for history already logged. The fold would grant them on first run and
  the whole collection would arrive at once; earns count from the phase's first launch.

## 9. Testing

**JVM (pure):** trigger detection from synthetic event lists, including the excluded-trigger
regression (a month with zero regrets earns nothing); the deterministic roll produces the same
species across repeated folds; pairing skips investments, weeds and zombies; an unpaired earn
stays pending; a rare tagged as regret becomes a zombie.

**The anti-farming regressions get their own tests, one per scope key in §3.3.** The
redemption one is explicit: a transaction toggled REGRET → WORTH_IT → REGRET → WORTH_IT emits
two `transaction.regret_cleared` events and must yield exactly **one** earn. Ten gate dodges
in a month must yield one earn, not eight.

**Instrumented:** the fold over a real Room database produces identical results on two
consecutive runs — the determinism guarantee that §4.1 depends on, verified rather than
assumed.

**Device:** earn an Uncommon, log a qualifying transaction, confirm the rare sprite renders
and the album updates.
