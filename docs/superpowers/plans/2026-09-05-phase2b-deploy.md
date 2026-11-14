# Phase 2B — Deploy: Runbook

**Goal:** core-api reachable over HTTPS from anywhere, at ₹0/month, redeploying on push.

**Why a runbook and not a spec + plan:** 2A left no design questions here. What remains is
account creation, environment variables, and pointing the app at a URL. A 2,000-line
implementation plan for "click these buttons" would be ceremony. The one genuine design
decision — which host — is recorded below with the evidence it was made on.

**Prerequisites, already done:** `Dockerfile`, `/v1/ready`, graceful shutdown and
`.github/workflows/ci.yml` landed in `041b89f`. Since `d7ec41f` and `939a597` the server fails
closed on an unset token, trims `SYNC_TOKEN`, and refuses one under 32 characters.

> **Revised 2026-11-13: users replace `SYNC_TOKEN`** (spec `2026-11-13-users-and-fc-history-design.md`).
> There is no `SYNC_TOKEN` any more: each user's token is created by `cmd/admin` and only its
> SHA-256 lives in `app_user`. §3, §5 and §7 below are rewritten for that.

> **Revised 2026-10-18: Koyeb → Cloud Run.** The first version of this runbook (2026-09-05)
> chose Koyeb. Re-checked on the day, as §1 requires, Koyeb had stopped being free for new
> accounts. (Superseded 2026-11-14 by Render; see §1.)

---

## 1. The hosting decision, and the evidence for it

> **Revised 2026-11-14: Cloud Run → Render, both in Singapore.** His call: no card on file
> anywhere. Google's free tier needs a billing account, and nothing in Google Cloud hard-caps
> spend. Render's free web service needs no card and cannot bill.

Re-checked 2026-11-14. Free tiers drift (this project has lost a Gemini model, Koyeb and now
Cloud Run's card-free path), so the rule stands: **re-check on the day you sign up.**

| Piece | Choice | Why |
|---|---|---|
| Database | **Neon** free plan, **AWS Singapore** | No card. 1 GB per project against a ledger of a few MB; 100 CU-hours a month; compute sleeps after 5 idle minutes and wakes on the next query. Running out suspends compute; it never deletes data. |
| Server | **Render** free web service, **Singapore** | No card, and no way to be billed. Deploys the existing `backend/core-api/Dockerfile` from GitHub on every push. `https://…onrender.com` included. 750 free instance hours a month per workspace. |
| Keep-awake | **cron-job.org** (free, no card) → `GET /v1/health` every 10 min | Render's free service sleeps after 15 idle minutes and takes about a minute to wake, which is longer than the phone's 30 s read timeout (`SyncClient.READ_TIMEOUT_MS`). Kept awake, one service uses ~720 of the 750 hours. `/v1/health` never touches Postgres, so Neon still sleeps and wakes in about a second on a real push. |
| Region | **Singapore for both** | Every push is several queries in one transaction, so server and database sit together; the phone in India is ~60 ms from Singapore against ~250 ms from the US. |

**What this costs, accepted:**
- **This must be the only free service in the Render workspace.** The 750 hours are shared, and
  two always-on services exhaust them; then every free service is suspended until next month.
- **If Render's terms change or the hours run out,** the server stops until next month. The
  phone keeps working fully (local-first) and backups resume when it returns. No bill is possible.
- **If the pinger stops,** the first backup after an idle spell times out and is retried on the
  next push: late, not lost.

**Rejected:**
- **Google Cloud Run** (chosen 2026-10-18): free, but needs a card on file with no spend cap.
- **Koyeb**: no longer free for new accounts; cheapest plan $29/month.
- **Vercel**: no card-free signup confirmed, and core-api would have to be rebuilt as functions.
- **Oracle Cloud, Fly.io, Railway**: all want a card.
- **Render without the pinger**: the ~1 minute wake is longer than the phone's 30 s timeout, so
  backups would systematically run late. **The host's wake-up time is a constraint on the
  client's timeout, not a footnote.**

---

## 2. Neon (needs Rajdweep)

- [ ] **Create a project** at neon.tech. Region: **AWS Asia Pacific (Singapore)**, next to the
      Render service (§5) — every push is several queries in one transaction, so the distance
      between the two is paid per push.
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

## 3. Your account on Neon (needs Rajdweep; after §2, before §7)

Run from `backend/core-api` on the Mac, pointed at Neon's direct string (it applies the
migrations first). The history is imported again rather than copied from the local database: his
local account holds nothing but the import, and the import is idempotent.

```bash
export DATABASE_URL='<the direct Neon string from §2>'
go run ./cmd/admin create-user rajdweep ~/Documents/fortune-city-export/rajdweep-neon.token
go run ./cmd/admin import-fc rajdweep ~/Documents/fortune-city-export/<the latest export>.json
```

The token goes to a new 0600 file and is never printed. Store it in your password manager.
**This is the credential that authorises a restore, so it must survive the phone.** It goes in
exactly one place: the app's Settings → Backup → token field (§7). Never paste it into a chat.
The import prints per-category counts and totals; they must match the local import.

---

## 4. The keep-awake pinger (needs Rajdweep; after §5, once the URL exists)

- [ ] Sign up at **cron-job.org** (free, no card).
- [ ] Create a cron job: URL `https://<the-service>.onrender.com/v1/health`, method GET,
      **every 10 minutes**, all day. No headers, no token: `/v1/health` is unauthenticated on
      purpose and touches nothing.
- [ ] Turn on its failure notification (email after a few failed runs). That email is your
      uptime alert.

---

## 5. Render (needs Rajdweep)

- [ ] Sign up at **render.com** with GitHub (your **personal** account, `Rajdweep1` — never the
      work one). Grant access to `Rajdweep1/Expense-garden` only.
- [ ] **New → Web Service** → `Rajdweep1/Expense-garden`.
      - Name `core-api`. Region **Singapore**. Branch `main`.
      - **Root Directory `backend/core-api`**, Runtime / Language **Docker** (it finds the
        `Dockerfile` there).
      - Instance type **Free**.
      - Environment variables:
        - `DATABASE_URL` = the direct Neon string from §2, pasted without a trailing newline.
        - `PORT` = `8080`. Render routes traffic to `$PORT` (10000 by default); the server
          listens on `:8080`, so this keeps the two in step with no code change.
        - No token variable: tokens live in the database (§3).
      - Advanced → **Health Check Path `/v1/health`** — never `/v1/ready` (below).
      - Auto-Deploy **on**, so a push to `main` redeploys. Advanced → Build Filters → include
        `backend/core-api/**`, so Android-only commits don't rebuild it.
- [ ] **Create Web Service.** The first build takes a few minutes; the page then shows the
      `https://…onrender.com` URL. Then set up the pinger (§4).

---

## 6. Verify the deployment (I can do this once the URL exists)

Send me the `https://…onrender.com` URL — only the URL. None of these checks needs the token:

```bash
BASE=https://<the-service>.onrender.com
curl -s -w '\n%{http_code}\n' $BASE/v1/health          # {"ok":true} 200
curl -s -w '\n%{http_code}\n' $BASE/v1/ready           # {"ready":true} 200 — proves Neon is wired
curl -s -o /dev/null -w '%{http_code}\n' $BASE/v1/sync/snapshot          # 401, no token
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer nope" $BASE/v1/sync/snapshot   # 401
```

A `200` on `/v1/health` but `503` on `/v1/ready` means the container is up and `DATABASE_URL`
is wrong — the single most likely failure, and the reason the two checks are separate. 

Then the cold start, measured rather than assumed: after the service has been idle for an hour,
run `curl -s -o /dev/null -w '%{time_total}s\n' $BASE/v1/ready`. That first call pays for the
instance starting and Neon waking; it has to come in comfortably under the phone's 30 s read
timeout.

The right token is proven end to end in §7, by the phone, so the token never needs to leave your
password manager and the phone.

---

## 7. Point the phone at it

In the app: Settings → Backup → type server url = the `https://…onrender.com` URL and token = §3's
token, then tap **Restore from backup — not Save**. The dialog should read 1,779+ payments; Restore
replaces the phone's test data with your history and saves the url and token. Save first would push
the phone's test rows into your account before the restore.

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

Sources checked 2026-11-14: [Neon plans](https://neon.com/docs/introduction/plans) ·
[Neon Free plan limits](https://neon.com/faqs/free-plan-limits-and-quotas) ·
[Neon connection pooling](https://neon.com/docs/connect/connection-pooling) ·
[Render: free tiers in 2026](https://render.com/articles/platforms-with-a-real-free-tier-for-developers-in-2026) ·
[Render free tier (agentdeals)](https://agentdeals.dev/vendor/render) ·
[Cloud Run pricing](https://cloud.google.com/run/pricing) (the rejected option).
