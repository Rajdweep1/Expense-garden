# Phase 2B — Deploy: Runbook

**Goal:** core-api reachable over HTTPS from anywhere, at ₹0/month, redeploying on push.

**Why a runbook and not a spec + plan:** 2A left no design questions here. What remains is
account creation, environment variables, and pointing the app at a URL. A 2,000-line
implementation plan for "click these buttons" would be ceremony. The one genuine design
decision — which host — is recorded below with the evidence it was made on.

**Prerequisites, already done:** `Dockerfile`, `/v1/ready`, graceful shutdown and
`.github/workflows/ci.yml` landed in `041b89f`. Since `d7ec41f` and `939a597` the server fails
closed on an unset token, trims `SYNC_TOKEN`, and refuses one under 32 characters.

> **Revised 2026-10-18: Koyeb → Cloud Run.** The first version of this runbook (2026-09-05)
> chose Koyeb. Re-checked on the day, as §1 requires, Koyeb had stopped being free for new
> accounts. Everything below §1 is written for Cloud Run.

---

## 1. The hosting decision, and the evidence for it

Verified 2026-10-18 against each provider's own pages. Free tiers drift — this project has now
lost a Gemini model mid-phase *and* its first choice of host — so the rule stands: **re-check on
the day you sign up.**

| Piece | Choice | Why |
|---|---|---|
| Database | **Neon** free plan | "Permanent (not a trial)", "no credit card required". 1 GB per project (was 0.5 GB on 2026-09-05) against a ledger measured in single-digit MB. Compute sleeps after 5 minutes idle and wakes on the next query. |
| Container | **Google Cloud Run**, request-based billing | Free tier with "no end date": 2 million requests, 180,000 vCPU-seconds and 360,000 GB-seconds a month, plus 1 GB of outbound data from North America — per billing account. Scales to zero; one user's pushes are a rounding error against those numbers. Deploys from the GitHub repo on every push via Cloud Build (2,500 free build-minutes a month). |
| Secrets | **Secret Manager** | 6 active secret versions and 10,000 access operations a month free. We need two. |
| TLS + domain | Cloud Run's `*.run.app` | HTTPS terminated for free. A custom domain costs money and would break the ₹0 constraint for no functional gain. |

**The catch, accepted:** Google requires a Cloud billing account to use the free tier, which in
practice means a card on file. Budget alerts (§4) only *warn*; nothing in Google Cloud hard-caps
spend. At one user's volume that risk is theoretical, and every comparable host now asks for a
card except Render, rejected below.

**Koyeb was rejected on 2026-10-18.** Its pricing page lists nothing below Pro at $29/month; its
FAQ (updated 2026-05-27) requires a card, verified with a $29 pre-authorisation hold, and uses Pro
as the example plan at signup; and the free Starter plan is reported as being removed for new
accounts during the Mistral Compute transition (Mistral agreed to acquire Koyeb on 2026-02-17). A
host that bills $29 a month fails the ₹0 constraint outright.

**Render was rejected for a specific, measurable reason.** Its free web services spin down and
take roughly 50 s to wake. A sleeping host's proxy accepts the connection and holds it while the
service boots, so that wait lands on `SyncClient.READ_TIMEOUT_MS = 30_000`: every push after an
idle period would time out, degrade to silence per spec §5, and leave the status line reading
stale — the backup would be systematically late while looking merely quiet. **The host's wake-up
latency is a constraint on the client's timeout, not a footnote.**

**Cold start is still visible after idle.** Cloud Run's front end accepts the connection at
once and queues the request while an instance starts; that instance then has to wake Neon and
run the (no-op) migrations before it listens. Nothing waits on any of it — the LLM and the sync
layer are both off every read path — and §6 measures it rather than trusting a number.

---

## 2. Neon (needs Rajdweep)

- [ ] **Create a project** at neon.tech. Region: a **US** one (for example AWS US East), near
      Cloud Run's `us-central1` — every push is several queries in one transaction, so the
      distance between the two is paid per push.
- [ ] **Copy the direct connection string**: in the Connect dialog, turn **Connection pooling
      off**. The pooled host has `-pooler` in its name; the direct one doesn't. Neon advises the
      direct string for schema migrations, and `migrations.Apply` runs on every boot; one user
      gains nothing from pooling. It looks like
      `postgresql://<user>:<password>@<endpoint>.<region>.aws.neon.tech/<db>?sslmode=require`.
      **Keep `sslmode=require`** — it is not optional over the public internet, and `pgx`
      honours it.
- [ ] Store it in your password manager. It is a credential with write access to the backup.
      Never paste it into a chat.

No schema work is needed: `migrations.Apply` runs on every boot and is a no-op once applied,
so the first deploy creates the tables itself.

---

## 3. Generate the sync token (needs Rajdweep)

Not a password you invent — 32 random bytes:

```bash
openssl rand -base64 32
```

That prints 44 characters; the server refuses anything under 32. Store it in your password
manager. **This is the credential that authorises a restore, so it must survive the phone.** It
goes in exactly two places: the `sync-token` secret (§5), and the app's Settings → Backup → token
field (§7). Never paste it into a chat.

---

## 4. Google Cloud: a personal project, billing, and a budget alert (needs Rajdweep)

- [ ] **Use a personal Google account — never your work account, and never a work project.**
      Check the account switcher (top right of console.cloud.google.com) before every step
      below. This repo must never touch the company's accounts.
- [ ] Create a project named `expense-garden`, under **No organization**.
- [ ] Billing → create a billing account with your card, and link it to `expense-garden`.
- [ ] Billing → **Budgets & alerts** → create a budget scoped to `expense-garden`: amount ₹10,
      thresholds at 10%, 50% and 100% of **actual** spend, emailed to you. The first alert then
      fires at ₹1. Expected spend is ₹0; this exists to say so loudly if that ever stops being
      true.

---

## 5. Cloud Run (needs Rajdweep)

- [ ] **Secrets first.** Security → Secret Manager (enable the API when asked). Create two
      secrets, each pasted without a trailing newline:
      - `database-url` = the direct Neon string from §2
      - `sync-token` = the token from §3
- [ ] **Create the service.** Cloud Run → **Connect repository** → **Cloud Build** → GitHub
      (authenticate when asked) → `Rajdweep1/Expense-garden` → **Next**.
- [ ] **Build configuration:** Branch `^main$`. Build Type **Dockerfile**. Source location
      `/backend/core-api/Dockerfile` — Google's docs say the Dockerfile's directory becomes the
      build context, which is exactly what the Dockerfile's `COPY go.mod go.sum ./` needs.
      **Save.**
- [ ] **Service settings:**
      - Name `core-api`, region **`us-central1`** (North America, where the free egress is).
      - Authentication: **Allow public access** (older consoles say "Allow unauthenticated
        invocations"). The app checks the bearer token itself; left on "Require
        authentication", Google answers 403 before a request ever reaches it.
      - Billing: **request-based** (CPU only while a request is in flight). The free tier above
        is for request-based billing.
      - Scaling: minimum **0**, maximum **1**. One instance is plenty for one user, caps any
        runaway cost, and means two cold starts can never run migrations concurrently —
        `migrations.Apply` takes no lock.
      - Container port **8080** (the default; the server listens on `:8080`). Memory and CPU at
        their defaults.
      - Variables & Secrets → **Reference a secret**: `DATABASE_URL` ← `database-url`, version
        `latest`; `SYNC_TOKEN` ← `sync-token`, version `latest`. Accept the prompt to grant the
        service account access.
      - Health checks: leave the default startup probe. If you add a liveness probe, make it
        HTTP **`/v1/health`**.
- [ ] **Create.** The first build and deploy take a few minutes; the service page then shows its
      `https://…run.app` URL.

> Use `/v1/health`, **not** `/v1/ready`, for any probe. Health is liveness and never touches
> the database; ready pings Postgres. Pointing the platform's restart trigger at a
> database-dependent check turns a Neon blip into a container crash loop. `/v1/ready` is for you
> to curl when diagnosing, not for the platform to police.

**Optional, recommended once it works:**
- Cloud Build → Triggers → edit the trigger the setup created, and set its included-files filter
  to `backend/core-api/**`, so Android-only commits don't rebuild the server. Confirm the filter
  field in the console; the continuous-deployment docs don't cover it.
- Artifact Registry → the repository the deploy created → **Cleanup policies**: keep the most
  recent 5 versions. Each deploy pushes an image, and the free tier is 0.5 GB of storage.

---

## 6. Verify the deployment (I can do this once the URL exists)

Send me the `https://…run.app` URL — only the URL. None of these checks needs the token:

```bash
BASE=https://<the-service-url>.run.app
curl -s -w '\n%{http_code}\n' $BASE/v1/health          # {"ok":true} 200
curl -s -w '\n%{http_code}\n' $BASE/v1/ready           # {"ready":true} 200 — proves Neon is wired
curl -s -o /dev/null -w '%{http_code}\n' $BASE/v1/sync/snapshot          # 401, no token
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer nope" $BASE/v1/sync/snapshot   # 401
```

A `200` on `/v1/health` but `503` on `/v1/ready` means the container is up and `DATABASE_URL`
is wrong — the single most likely failure, and the reason the two checks are separate. A `403`
from every route means the service still requires authentication (§5).

Then the cold start, measured rather than assumed: after the service has been idle for an hour,
run `curl -s -o /dev/null -w '%{time_total}s\n' $BASE/v1/ready`. That first call pays for the
instance starting and Neon waking; it has to come in comfortably under the phone's 30 s read
timeout.

The right token is proven end to end in §7, by the phone, so the token never needs to leave your
password manager and the phone.

---

## 7. Point the phone at it

In the app: Settings → Backup → server url = the `https://…run.app` URL, token = §3's token,
then Save.

> **`https` is now mandatory, not preferred.** `network_security_config.xml` is strict in the
> `main` source set, so a release build physically cannot talk to an `http://` URL. The debug
> override permits cleartext only to `10.0.2.2` / `localhost` / `127.0.0.1`. If a real host is
> ever entered with `http://`, it fails closed — which is the correct behaviour, and it will
> look like silence, so check the Settings status line first.

Then: log a transaction and confirm the status line reads "Backed up just now", and that the
row appears in Neon (Neon's SQL editor: `SELECT count(*) FROM txn;`).

---

## 8. Then, and only then, the local server stops mattering

Until this is done, the backup protects against losing the **phone**, not against losing the
**Mac** — core-api and Postgres both live on it. That is the whole point of 2B, and it is
worth saying plainly because the app looks identical either way.

`pkill -f core-api` retires the local one. Note the trap from bring-up: `kill %1` kills the
`go run` wrapper and leaves the compiled child holding :8080.

---

## 9. Out of scope, deliberately

- **Backups of the replica** — Neon holds the only server-side copy, and nothing dumps it yet.
  That is 2C, and until it lands the honest description is "two copies, both live".
- **Sentry.** 2C.
- **Custom domain.** Costs money.
- **Staging.** One user, one environment.

Sources checked 2026-10-18: [Neon pricing](https://neon.com/pricing) ·
[Neon connection pooling](https://neon.com/docs/connect/connection-pooling) ·
[Google Cloud free tier](https://docs.cloud.google.com/free/docs/free-cloud-features) ·
[Cloud Run continuous deployment](https://docs.cloud.google.com/run/docs/continuous-deployment-with-cloud-build) ·
[Koyeb pricing](https://www.koyeb.com/pricing) · [Koyeb pricing FAQ](https://www.koyeb.com/docs/faqs/pricing) ·
[Koyeb scale-to-zero](https://www.koyeb.com/docs/run-and-scale/scale-to-zero) ·
[SnapDeploy: state of free hosting](https://snapdeploy.dev/state-of-free-hosting).
