# CLAUDE.md — expense-garden

Personal gamified UPI expense tracker for Android, plus a Go sync replica. Dual goal: a
daily-use tool for Rajdweep, and a portfolio piece showcasing deliberate, justified
architecture. Hard constraint: everything runs at ₹0/month.

## Read these first
- `README.md` — what the project is and the decisions worth knowing
- `docs/superpowers/specs/2026-07-03-expense-garden-design.md` — the parent design spec (the WHAT and WHY). Note its Phase 2/3 sections predate later decisions; see Roadmap below.
- The spec + plan for whatever phase you're touching, in `docs/superpowers/specs/` and `docs/superpowers/plans/`

## Working with Rajdweep (non-negotiable)
- **Plan first.** Present a plan and get explicit confirmation before writing code.
- **Commits:** plain messages, NO `Co-Authored-By` / Claude / AI attribution lines.
- **Never `git push` unless explicitly asked.** A remote now exists (see below), so this
  matters more than it used to.
- **Never auto-commit docs/specs/plans** — leave them as uncommitted working files unless
  he says otherwise. Code commits are fine.
- Subagents and workflows are permitted, but he has asked for inline work by default —
  don't fan out dozens of agents for something you can do yourself. Whatever a subagent
  reports, verify its evidence before repeating it to him.
- He's a senior Python backend dev (FastAPI/LangGraph at SiteRecon); new to Android/Kotlin;
  Go is a deliberate learning goal. Explain Android-isms briefly. Give ONE recommendation
  with honest pushback, not option surveys.

## Git identity — this repo is PERSONAL
The machine is globally wired to his **company** GitHub. This repo must never touch it.

- Remote: `git@github-personal:Rajdweep1/Expense-garden.git` (an SSH host alias in
  `~/.ssh/config` pinned with `IdentitiesOnly yes`, so the work key cannot be offered).
- Repo-local `user.email = rajdweepmondal@gmail.com`. Do not touch global git config.
- Verify with `ssh -T git@github-personal` → "Hi Rajdweep1!"

## Commands

Every Gradle command needs JDK 17 — the default `java` on this machine is 11 and AGP 8.5.2
rejects it. Each shell starts fresh, so export it on *every* command:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew testDebugUnitTest                 # JVM unit tests (181)
./gradlew connectedDebugAndroidTest         # instrumented (56) — needs an emulator/device
./gradlew installDebug                      # build + install
```

Filtered runs: `testDebugUnitTest --tests '<pattern>'` and
`connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`.
Bare `./gradlew test` works, but `test --tests` does not — it's a lifecycle task with no
filter option.

**`connectedDebugAndroidTest` uninstalls both APKs when it finishes.** Chaining
`installDebug` before it in one command leaves the device with no app. Install last, alone.

Backend:

```bash
cd backend/core-api
DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" \
  SYNC_TOKEN=<generated> go run ./cmd/api
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" go test ./...
```

Emulator: `~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim`.
`adb` is not on PATH — it's at `~/Library/Android/sdk/platform-tools/adb`.

## Implementation guardrails
- **Do NOT upgrade versions or add dependencies.** `gradle/libs.versions.toml` is
  deliberately pinned. The "you'd normally use Retrofit / OkHttp / DataStore / WorkManager /
  Hilt here" instinct is wrong for this repo — both HTTP clients are `HttpURLConnection` +
  `org.json` on purpose. The Go module may take dependencies; the freeze is Android-only.
- **Do NOT "fix" deprecation warnings.** Acceptable ones are called out in the plans.
- **If a plan step's output doesn't match its Expected line: stop and report.** Don't improvise.
- **Room schema hazard:** building with modified entities but the OLD `version =` silently
  overwrites the *previous* version's schema JSON. Order is always: edit entities → bump
  version → build → read the generated SQL → write the migration.
- **Instrumented test names must be snake_case.** `minSdk 26` means D8 targets a DEX version
  that rejects spaces in method names; backtick-with-spaces names are fine in `app/src/test`
  and fatal in `app/src/androidTest`.
- Dev loop is the emulator. The physical phone is needed only for the real-payment E2E —
  his explicit choice: real payments come absolutely last.

## Architecture invariants
- **Local-first.** Room (SQLite) on the phone is the source of truth; the backend is a
  replica. Nothing may ever sit in the payment path.
- **Money is paise as `Long`.** Never floats.
- **`game_event` is append-only** — nothing updates or deletes it — and the garden is a pure
  fold over it. Emitted atomically with every transition to LOGGED.
- **The LLM is never in a read path.** The AI layer only writes to `quip` and `digest`;
  screens only read them. That is what makes the gate work offline — structurally, not by
  timeout.
- **Never punish the log.** Loss-aversion pressure lives at the pre-payment gate only;
  necessities are never shamed; every bad state has a redemption path.
- **Sync cursors are monotonic ids or a logical clock, never wall-clock time.** A timestamp
  cursor over colliding timestamps loses rows; this has bitten twice.
- **DB standards:** every FK explicit with a deliberate ON DELETE; encode invariants as
  constraints; Room schema history is committed under `app/schemas/` (currently v4).

## Roadmap and status
Phase 1: **1A capture core** (done bar the real-payment E2E) → **1B budgets + dashboard**
(done) → **1C garden** (done, through 1C.7) → **1D AI** (done, verified on device) →
**1E Fortune City CSV import** (not started — verify FC's CSV export actually exists first).

Phase 2 backend, split three ways: **2A sync core** (done — Go core-api + Postgres,
wipe-and-restore verified) → **2B deploy** (runbook written; Neon + Koyeb, awaiting signup)
→ **2C durability** (backups, Sentry — deferred).

**Phase 3 (`ai-svc`) is dropped.** 1D delivers persona and digests on-device at ₹0 with the
LLM outside every read path; moving it server-side would spend the offline guarantee for
richer context. If Play Store distribution ever needs an API-key proxy, that lands in
core-api instead.

Phase 4 showcase: gamification depth, read-only web dashboard (needs a deployed core-api),
Play Store hardening, investments via CAS parsing.

The parent spec's Phase 1 acceptance is *"done when it's the default way its user pays
offline merchants."* That is a usage bar, not a code bar.
