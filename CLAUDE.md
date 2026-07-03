# CLAUDE.md — expense-garden

Personal gamified expense tracker for Android (+ later a small self-hosted backend).
Dual goal: a daily-use tool for Rajdweep, and a portfolio piece showcasing deliberate,
justified architecture. Hard constraint: everything runs at ₹0/month.

## Read these first
- `docs/superpowers/specs/2026-07-03-expense-garden-design.md` — approved design spec (the WHAT and WHY)
- `docs/superpowers/plans/2026-07-03-phase1a-capture-core.md` — hardened Phase 1A implementation plan (the HOW; includes agent guardrails and the hardening-review log)

## Working with Rajdweep (non-negotiable)
- Plan first: present a plan and get explicit confirmation before writing code.
- Commits: plain messages, NO Co-Authored-By / Claude lines. NEVER push unless asked. (No remote is configured yet anyway.)
- Explore the codebase directly (Read/Grep/Bash) — don't dispatch subagents for analysis.
- He's a senior Python backend dev (FastAPI/LangGraph at SiteRecon); new to Android/Kotlin; Go is a deliberate learning goal for Phase 2. Explain Android-isms briefly as they come up. Give recommendations, not option surveys.

## Commands
```bash
./gradlew test                        # JVM unit tests (parser, gate, money)
./gradlew connectedDebugAndroidTest   # Room/DAO tests — needs a running emulator or device
./gradlew installDebug                # build + install on the connected target
adb devices                           # verify the emulator/phone is visible
```

## Implementation guardrails (Phase 1A)
- Do NOT upgrade versions, add dependencies, or "fix" deprecation warnings — the pinned
  matrix in `gradle/libs.versions.toml` is deliberate; acceptable deprecations are called
  out in the plan steps.
- If a plan step's output doesn't match its Expected line: stop and report. Don't improvise.
- Dev loop runs on the Android emulator (QR scanning works there via the virtual-scene
  camera poster trick). The physical phone is needed only for Task 12's real-payment E2E —
  the user's explicit choice: real payments come absolutely last.

## Architecture invariants
- **Local-first.** Room (SQLite) on the phone is the source of truth; any future backend is
  a replica. Nothing may ever sit in the payment path.
- **Money is paise as `Long`.** Never floats.
- **`game_event` is append-only**, emitted atomically with every transition to LOGGED — the
  garden (Plan 1C) replays history from it.
- **Never punish the log.** Loss-aversion pressure lives at the pre-payment gate only;
  necessities are never shamed; every bad state has a redemption path.
- **Persona: "sharp but fair"** default. Gate lines come from the local quip cache — the
  gate never blocks on an LLM call and must work offline.
- **DB standards:** every FK explicit with a deliberate ON DELETE; encode invariants as
  constraints; Room schema history is committed under `schemas/`.

## Roadmap
Phase 1 = five plans: **1A capture core (current)** → 1B per-category budgets + dashboard →
1C garden (event fold + Canvas renderer) → 1D AI (Gemini free tier, swappable `LlmClient`,
digest, quip refresh) → 1E Fortune City CSV import (verify FC export exists first).
Then: Phase 2 backend (Go core-api + Postgres on Oracle Always Free VM, docker-compose,
sync, backups, CI) → Phase 3 ai-svc (Python persona/digests) → Phase 4 showcase
(web dashboard, Play Store hardening, investments).
