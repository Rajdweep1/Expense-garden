# Expense Garden — Design Spec

**Date:** 2026-07-03
**Status:** Approved pending final review
**Working title:** `expense-garden` (rename freely; app name is an open question)

## 1. Summary

A personal, gamified expense tracker for Android. Spending is captured at the moment
of payment via a QR front-door (the app launches the real UPI app), visualized as a
living garden, and narrated by a resident AI character with a "sharp but fair" roast
persona. Local-first: the phone is the source of truth; a small self-hosted backend
is phased in for sync, scheduled AI, and an eventual web dashboard.

Dual purpose: (1) a daily-use tool for its one user, (2) a portfolio piece that
showcases deliberate architecture — two justified services, a written decision log,
and restraint over service sprawl.

## 2. Goals

- Capture spending with near-zero friction (scanning from this app = same effort as scanning from GPay).
- Soft-block overspending: a gamified warning at the payment gate — never a hard block.
- An AI that has full context of spending history: answers, suggests, roasts, remembers.
- Full dashboard: stats, graphs, budgets by category/subcategory.
- Forecasting from history (recurring detection, pace projection, seasonality) — classical stats, no model training.
- Run at ₹0/month on free tiers.
- Play-Store-viable later without re-architecture (capture path uses no restricted permissions).

## 3. Non-goals (v1)

- No SMS or notification parsing (layer-2 capture, deferred; QR front-door + manual entry only).
- No hard payment blocking (structurally impossible for a third-party app; gate is friction, not enforcement).
- No investment tracking until Phase 4 (CAS-statement parsing is well-trodden; lowest priority).
- No multi-user, no auth beyond a single-user JWT, no monetization.
- No LLM training or fine-tuning, ever. Stats are computed deterministically; the LLM narrates.

## 4. Decisions at a glance

| Topic | Decision |
|---|---|
| Client | Android, Kotlin + Jetpack Compose, local-first (Room/SQLite = source of truth) |
| Capture | QR front-door: scan UPI QR → type amount in-app → persona gate → fire `upi://pay` intent → confirm sheet on return |
| Blocking | Soft only: persona gate previews consequence ("this plants a weed"), user can always proceed |
| Game | Concept B: living garden + one resident roaster character; event-driven state machine; renderer = swappable skin |
| Persona default | "Sharp but fair" (intensity knob with Savage / Gentle presets = prompt swaps) |
| Backend | Oracle Always Free ARM VM (2 OCPU / 12GB post-June-2026 cut), docker-compose |
| Services | Exactly two: `core-api` (Go) + `ai-svc` (Python). Splits justified in §7 |
| DB | Postgres 16 container, own schema, FKs everywhere, numbered SQL migrations; nightly `pg_dump` → Cloudflare R2 |
| LLM | Swappable client. Default: Gemini 2.5 Flash free tier (~1,500 req/day). Fallbacks: Groq, OpenRouter free. Privacy option: Ollama on Mac via Tailscale |
| Cost | ₹0/month. Every component on a permanent free tier or self-hosted |
| Deferred (with reasons) | Redis, Pinecone, Clerk, message queue, PostHog, further service splits — see ADR list §7.4 |

## 5. Core loops

### 5.1 Capture loop (the product)

1. User taps scan (or app shortcut / QS tile), scans any UPI QR.
2. App parses the `upi://pay?pa=&pn=...` URI → payee identified (or created).
3. User types the amount **in this app**; category suggested from the personal
   payee→category map, else LLM fallback, else quick-pick.
4. **The gate:** app computes budget severity locally (ok / pace-warning / breached).
   The resident character delivers one line + two buttons: proceed / back out.
   - Gate lines come from a **pre-generated quip cache** (see §8.4) — the gate is
     latency- and offline-critical and must never block on an LLM call.
5. On proceed: transaction saved as `pending_confirm`, UPI intent fired, Android
   chooser opens the real UPI app. (Amount prefill quirks in other apps are
   irrelevant — we already logged intended amount + payee.)
6. On return to app (or next open): one-tap confirm sheet — "went through? log / discard."
   Unresolved `pending_confirm` rows re-prompt on next launch.
7. Confirmed transaction emits `transaction.logged` → a seed is planted. **Logging always grows something.**

**Manual entry is a first-class path, not a fallback** — cash, offline payments,
anything the QR flow missed. Same amount/category/payee UI as step 3, but it is
post-hoc: the money is already spent, so it skips the gate *decision* (the persona
may still quip on save) and the row goes straight to `logged` — no `pending_confirm`,
no UPI round-trip. `occurred_at` is user-settable for backdated logs ("paid this
morning, logging tonight"). Game rules apply identically: a manual log plants a seed
like any other.

### 5.2 Review loop (weekly)

- Weekly review surfaces flagged discretionary expenses: "worth it?" → `worth_it` / `regret`.
- A `regret` answer converts the plant to a weed and feeds the roast corpus.
- Same honest answer powers both the game and the AI. Necessities are never surfaced here.

### 5.3 Engagement loop (daily)

- Garden reflects the month; weather reflects budget health.
- Daily digest (if anything notable — silence rule otherwise).
- Streaks, redemption mechanics, month-end plot archival into the greenhouse album.

## 6. Client architecture (local-first)

Local-first is non-negotiable even after the backend exists: capture happens in a
shop with flaky network while paying someone. Scan → gate → log → intent must have
zero server dependency. **Room is the source of truth; Postgres is the replica** —
the backend serves sync, heavy AI, and scheduled digests, and must never sit in the
payment path.

Modules (one codebase, boundaries deliberate):

- `capture/` — QR scanning, upi:// parsing, intent firing, confirm flow
- `ledger/` — transactions, payees, categories, budgets (Room)
- `stats/` — aggregates, baselines, recurring detection, pace projection (pure Kotlin, unit-testable)
- `ai/` — LLM client interface (Gemini/Groq/Ollama/ai-svc implementations), context assembly, quip cache
- `game/` — event-driven state machine; consumes ledger events, owns world state
- `render/` — garden skin over `game/` state (Compose Canvas; placeholder art first)
- `sync/` — push/pull against core-api (Phase 2+)

## 7. Backend architecture

```
┌────────────── Android app · Kotlin + Compose ──────────────┐
│ QR scan → amount → warn/roast gate → UPI intent → confirm  │
│ Room (SQLite) = source of truth · offline-first · game UI  │
└─────────────┬──────────────────────────────┬───────────────┘
              │ sync (REST)                  │ chat / roast
┌─────────────▼──────────────────────────────▼───────────────┐
│        Oracle Always-Free ARM VM · docker-compose          │
│   Caddy (TLS) → core-api (Go)      → Postgres 16           │
│                 ledger·sync·stats     schema w/ FKs,       │
│                 budgets·auth(JWT)     numbered migrations  │
│                 ai-svc (Python)    → Gemini/Groq free tier │
│                 persona·context       (swappable client,   │
│                 digests·quip gen      Ollama fallback)     │
│   nightly pg_dump → Cloudflare R2 (10GB free)              │
└────────────────────────────────────────────────────────────┘
  GitHub Actions CI/CD · Sentry (app + both services) · CF DNS
```

### 7.1 core-api (Go)

The always-up, boring-on-purpose service: ledger sync, server-side stats, budgets,
single-user JWT auth. Go is a deliberate learning goal, placed in the service where
correctness matters most.

### 7.2 ai-svc (Python)

Persona, context assembly, categorization fallback, scheduled digest jobs, quip-cache
generation. Separate service because: LLM providers churn (Gemini → Groq → Ollama)
and that churn must be isolated from the ledger; the LLM tooling ecosystem is Python;
the roast service dying must never take the ledger down. Also the future Play Store
API-key proxy — it already exists in this shape.

### 7.3 Sync model

Client-generated UUIDs, `updated_at` + tombstones, last-write-wins per row (safe for
a single user across devices). Push-pull REST; no realtime.

### 7.4 Deliberately deferred (ADR log — each gets a one-line ADR in `docs/adr/`)

- **Redis:** nothing to cache for n=1. Revisit at multi-user.
- **Pinecone / vector DB:** a few thousand transactions; SQLite FTS + SQL filters suffice. LLMs already know merchants.
- **Clerk / auth provider:** one user; a static JWT + TLS is proportionate.
- **Message queue:** scheduled jobs are cron-shaped; no fan-out exists.
- **PostHog:** the app *is* the analytics, about its only user. Maybe Phase 4 for practice.
- **Further microservice splits:** two services, each with a written justification; more would be cosplay.

### 7.5 Free-tier risk notes

- Oracle idle-reclaim: flagged idle if 95th-percentile CPU < 20% over 7 days → cron
  heartbeat + real usage; document the PAYG-upgrade escape hatch (card on file, still
  ₹0 within always-free shapes) but don't take it by default.
- Oracle signup capacity (esp. Mumbai): retry/scripted signup; fallback = Neon (free
  serverless Postgres) + Koyeb free container — workable, weaker portfolio story.
- Free-tier drift anywhere → the swappable-client pattern is the general defense.

## 8. AI layer

### 8.1 Principle: context, not training

All numbers are computed deterministically in `stats/` (SQL + Kotlin/Go). The LLM
receives a **context bundle** — this month's aggregates, category baselines,
anomalies, streaks, relevant memories, recent transactions — plus a persona system
prompt, and narrates. No fine-tuning anywhere; n=1 data can't support it and it
would trade general wit for memorized patterns.

### 8.2 Provider strategy

`LlmClient` interface with four implementations: Gemini free tier (default), Groq
free (fallback), OpenRouter free (fallback), Ollama-over-Tailscale (privacy option,
Mac must be awake). Volume design keeps usage trivial: rules-first categorization
(payee→category map learns from corrections), LLM only for unknown payees, chat,
digests, and quip generation — ~20–50 calls/day vs a 1,500/day free ceiling.
Privacy note recorded: free-tier providers may train on inputs; the Ollama path
exists for the day that itches.

### 8.3 Prediction (the "ML")

Recurring-payment detection (periodicity), category baselines, month-pace projection,
seasonality from the 3-year Fortune City import. Classical statistics; Prophet-style
time series at most, as an optional toy. Explicitly not a training problem.

### 8.4 Quip cache (gate latency)

ai-svc (Phase 1: on-device job) periodically pre-generates a bank of gate lines per
(severity × category-family × tone), stored locally. The gate selects locally with
zero latency and full offline capability; the bank refreshes in the background. The
no-repeat rule consumes quips on use.

## 9. Game layer

### 9.1 Design principle

**Never punish the log — punish only the choice, and only before it's made.**
Logging always grows something; loss-aversion pressure lives at the gate;
necessities are never shamed; every bad state has a redemption path.

### 9.2 Mechanics: event-driven state machine

`ledger/` emits events (`transaction.logged`, `transaction.regretted`,
`budget.pace_warning`, `budget.breached`, `streak.hit`, `month.closed`, …).
`game/` folds events into world state (plots, plants, weather, collections).
`render/` is a skin — garden today, aquarium later, mechanics untouched.
`game_event` is append-only: the world is replayable from history.

### 9.3 Financial event → garden mapping

| Financial event | Garden response |
|---|---|
| Any logged transaction | A seed is planted — always |
| Needed/recurring (rent, groceries, SIP) | Perennials and hedges — dignified at any amount |
| Discretionary within budget | Flowers/fruit — species by subtype, size by amount |
| Discretionary past budget, or regret-tagged | Weeds/thorns/odd mushrooms — distinct, mildly embarrassing, still collectible |
| Investments | Back-row trees; never reset monthly; each SIP thickens the trunk |
| Budget health | Weather: sunny → overcast → drought (mood, never destruction) |
| Under-budget streak / no-spend day | Rain, blooms, butterfly visit; streaks unlock rare species |
| A week back under budget | Weeds compost into fertilizer (boosts next planting) |
| Month end | Plot archives into the greenhouse album; fresh bed opens |

Weed rule, precisely: `discretionary AND (category breached at time of logging OR later regret-tagged)`. The necessity flag lives on the category, overridable per transaction.

### 9.4 Art scope (where solo projects die)

Mechanics first, placeholder art always shippable. Flat vector, ~10 plant archetypes
× 3 states, procedural variation (hue/scale/position) on Compose Canvas.
AI-generated asset packs acceptable for v1. Character: one resident roaster,
**species TBD** (crow vs gnome — cosmetic, decided during build, mechanics identical).

## 10. Persona

- **Default: Sharp but fair.** Sarcastic at the gate and weekly review; dry wit in
  digests; unprompted only on real signal (anomaly, pace-to-breach, streak
  milestone); intensity scales with overage + regret history; praises when earned.
- Presets Savage / Gentle exist behind an intensity setting — same context bundle,
  different system prompt; near-zero marginal cost.
- **Boundaries (all presets):** necessities off-limits (hedges, never mocked);
  roasts choices, not the person — no income digs, no comparisons, no doom; gate =
  one line + buttons, never a lecture; silence rule (nothing notable → nothing
  said); "not today" button mutes personality 24h without disabling logging;
  no-repeat quip memory.
- Surfaces: payment gate (cached quips), daily digest, weekly review, free chat,
  memories ("that weed patch? the ₹4k Swiggy week. I remember.").

## 11. Data model (entities; full DDL in the implementation plan)

Postgres and Room mirror the same shapes. Every relationship is a real FK with an
explicit `ON DELETE`; every table answers "what must never be stored here?" with a
constraint.

- `category` — name, `parent_id` self-FK (type/subtype), `is_necessity`
- `payee` — name, `vpa` UNIQUE nullable, `default_category_id` FK
- `transaction` — client UUID pk, `amount CHECK (amount > 0)`, payee FK, category FK,
  `source` enum (`qr_gate|manual|import`), `status` enum (`pending_confirm|logged|discarded`),
  `regret` enum (`unrated|worth_it|regret`), `occurred_at`, note, `series_id` FK nullable
- `budget` — category FK nullable (null = overall), `month`, amount, `UNIQUE(category_id, month)`
- `recurring_series` — detected periodicity metadata
- `game_event` — append-only; type, payload jsonb, transaction FK nullable
- `plant` / world state — plot-month, species, state, source event FK
- `persona_quip` — text hash, context key, `used_at` (no-repeat memory)
- `persona_memory` — notable moments for callbacks
- sync metadata — `updated_at`, tombstones

## 12. Fortune City import

3+ years of history seeds baselines, seasonality, and persona memories from day 1.
**Precondition to verify before building the importer:** CSV export is a premium
feature and some recent Play reviews claim it's missing — check the app's Settings
first. Fallbacks: SPARKFUL support, community converter tooling, or manual export.
Importer maps FC categories → our taxonomy; imported rows get `source = import` and
plant retroactive greenhouse-album months (no live-garden replay).

## 13. Phases & acceptance criteria

**Phase 1 — the app, fully local.**
Scan→gate→intent→confirm loop used for real daily payments; manual entry; categories
+ budgets; dashboard (month stats, category breakdown, pace); FC import done; roast
digest via direct-from-phone LLM; garden renders events with placeholder art.
*Done when it's the default way its user pays offline merchants.*

**Phase 2 — the VM.**
Oracle VM live with TLS; Postgres + migrations; Go core-api; sync round-trip proven
(wipe app → restore from server); nightly backup to R2 **verified restorable**;
GitHub Actions deploys on push to main; Sentry wired.

**Phase 3 — ai-svc.**
Persona/context assembly moves server-side; scheduled daily digest + weekly review
notifications; quip-cache generation pipeline; memories over imported history.

**Phase 4 — showcase.**
Gamification depth (collections, rare species, redemption polish); read-only web
dashboard (Vercel → core-api); Play Store hardening: key proxy (= ai-svc), privacy
policy, data-safety form; investments via CAS parsing.

## 14. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Oracle reclaim / signup capacity | Heartbeat cron; documented PAYG escape hatch; Neon+Koyeb fallback |
| Free-tier terms drift | Swappable clients everywhere (LLM, DB, host); decisions in ADRs |
| UPI intent quirks per PSP app | Test matrix against own installed apps; confirm-sheet already absorbs the worst case |
| Amount prefill inconsistency | Accepted: our log has intended amount; prefill is convenience only |
| Art scope creep | Placeholder-art rule; mechanics/render split; ship ugly first |
| Solo motivation | Phase 1 delivers daily utility in weeks; each phase is a README chapter |
| FC export unavailable | Verify first (§12); converters/manual fallback; app works without import |
| Play Store later | Capture path already uses zero restricted permissions; notification listener (layer 2) gets its own disclosure review when added |

## 15. Open questions (non-blocking)

- App name (working title `expense-garden`).
- Roaster species: crow vs gnome vs other (cosmetic; during build).
- Art style direction for v2 (post-placeholder).
- Notification-listener layer 2: scope and timing (post-Phase 2 decision).
