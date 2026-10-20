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
- He's a senior Python backend dev (FastAPI/LangGraph); new to Android/Kotlin;
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

**Do NOT point this at Android Studio's bundled JBR.** It used to work, then Android Studio
auto-updated its JBR to JDK 25 and every Gradle command started dying with
`java.lang.IllegalArgumentException: 25.0.3` from Kotlin 2.0.20's embedded IntelliJ version
parser — before compiling a single line. An app bundle that silently self-updates is not a
toolchain pin. Use the keg-only Homebrew JDK, whose path is stable across 17.x patches:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

./gradlew testDebugUnitTest                 # JVM unit tests (408)
./gradlew connectedDebugAndroidTest         # instrumented (95) — needs an emulator/device
./gradlew installDebug                      # build + install
./gradlew installRelease                    # the phone's daily build: not debuggable, same key
```

Filtered runs: `testDebugUnitTest --tests '<pattern>'` and
`connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`.
Bare `./gradlew test` works, but `test --tests` does not — it's a lifecycle task with no
filter option.

**`connectedDebugAndroidTest` uninstalls both APKs when it finishes.** Chaining
`installDebug` before it in one command leaves the device with no app. Install last, alone.

**Once the phone holds the real ledger, never run `connectedDebugAndroidTest` with it plugged in.**
The task runs on *every* attached device, and its uninstall deletes the app's data — ledger
included. Charging the phone from the Mac while testing on the emulator is enough to do it. Pin the
target instead: `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. The only
fallback is Android Auto Backup, which deliberately includes the Room DB
(`res/xml/backup_rules.xml`) but runs at most once a day, and only when the phone is idle and (by
default) on Wi-Fi — so a wipe loses at least that day's entries, and everything if backup is off on
the phone. 2B's server is the real fix, and it isn't deployed. The same goes for the debug keystore:
`~/.android/debug.keystore` signs the app that holds the ledger (the release build too), so if an
install ever fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (new machine, Android Studio
reinstall), restore the old keystore — never "fix" it by uninstalling.

Backend:

```bash
cd backend/core-api
DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden?sslmode=disable" \
  SYNC_TOKEN="$(openssl rand -base64 32)" go run ./cmd/api
# Tests DELETE every row in the database they point at: never the dev server's.
TEST_DATABASE_URL="postgres://$(whoami)@localhost:5432/expense_garden_test?sslmode=disable" go test ./...
```

Emulator — the bare `-avd` command is not enough, all three flags matter:

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim -gpu host -no-snapshot-save
```

`-gpu host` is **required**, not a speed-up: the AVD ships `hw.gpu.enabled=no` in
`~/.android/avd/Pixel_8_API_35.avd/config.ini`, so without the flag it renders in software and
System UI starts throwing ANRs mid-session. `-no-snapshot-save` stops the quicksave being rewritten
on every clean shutdown — it had grown to 2.5 GB of a 5.5 GB AVD before being deleted on
2026-10-11. The cost is a cold boot (~35s) instead of a snapshot restore, which is the better trade
on a machine that runs out of RAM before it runs out of patience. Don't add `-memory`: the config
already pins `hw.ramSize=2G`.

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

Phase 2 backend, split three ways: **2A sync core** (done — Go core-api + Postgres, auth
hardened 2026-10-18, wipe-and-restore verified) → **2B deploy** (runbook written; Neon + Google
Cloud Run — Koyeb dropped 2026-10-18 when it stopped being free for new accounts; awaiting
signup) → **2C durability** (backups, Sentry — deferred).

**Phase 3 (`ai-svc`) is dropped.** 1D delivers persona and digests on-device at ₹0 with the
LLM outside every read path; moving it server-side would spend the offline guarantee for
richer context. If Play Store distribution ever needs an API-key proxy, that lands in
core-api instead.

Phase 4 showcase: **4A collections** (done — rare species earned by restraint, never by
spending) → **4B landmarks** (done — koi pond and stone lantern on reserved plots) → **the design
pass** (done 2026-09-13 — `GardenTheme` derived from `GardenPalette`, window insets, launcher
icon; contrast/chroma floors are enforced by JVM tests, so never hand-pick a chrome colour
without running `GardenTokensTest`) → **the gate redesign** (done 2026-09-17 — necessities get a
neutral card with no quip and no gate-dodge credit, breaches preview the actual weed, pace
warnings name the streak; `StreakMath` and `GardenFolder.noSpendByMonth` now floor at the day the
ledger began, so a fresh install stops earning rares and streaks for days it wasn't there) →
**the review fixes** (done 2026-10-11/18 — budget Save can't clear, home chrome readable in dark
mode, every colour role from a token, fixed-cost detection floors at the ledger's start, upright
scanner, drafts survive process death) → **the November-ready fixes** (done 2026-10-19 — budgets
carry forward, rewards need a budget in force, an honest gate card, undo, the release build) →
read-only
web dashboard (needs a deployed core-api), Play Store hardening, investments via CAS parsing.

The parent spec's Phase 1 acceptance is *"done when it's the default way its user pays
offline merchants."* That is a usage bar, not a code bar.
