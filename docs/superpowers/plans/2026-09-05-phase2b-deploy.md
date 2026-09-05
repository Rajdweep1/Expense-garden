# Phase 2B — Deploy: Runbook

**Goal:** core-api reachable over HTTPS from anywhere, at ₹0/month, redeploying on push.

**Why a runbook and not a spec + plan:** 2A left no design questions here. What remains is
account creation, environment variables, and pointing the app at a URL. A 2,000-line
implementation plan for "click these buttons" would be ceremony. The one genuine design
decision — which host — is recorded below with the evidence it was made on.

**Prerequisite, already done:** `Dockerfile`, `/v1/ready`, graceful shutdown and
`.github/workflows/ci.yml` all landed in `041b89f`.

---

## 1. The hosting decision, and the evidence for it

Verified 2026-09-05, not carried over from the parent spec's 2026-07 note. Free tiers drift:
this project has already lost a Gemini model mid-phase, so the rule is to re-check on the day.

| Piece | Choice | Why |
|---|---|---|
| Database | **Neon** free tier | Permanent, not a trial. 0.5 GB per project against a ledger measured in single-digit MB. No time-based expiry — where Supabase pauses after 7 days idle and RDS's free year simply ends. |
| Container | **Koyeb** free instance | 512 MB / 0.1 vCPU, no card in the normal case, and a **1–5 s cold start**. |
| TLS + domain | Koyeb's `*.koyeb.app` | HTTPS terminated for free. A custom domain costs money and would break the ₹0 constraint for no functional gain. |

**Render was rejected for a specific, measurable reason.** Its free web services spin down and
take roughly 50 s to wake, against `SyncClient.CONNECT_TIMEOUT_MS = 10_000`. Every push after
an idle period would time out, degrade to silence per spec §5, and leave the status line
reading stale — the backup would be systematically late while looking merely quiet. Koyeb's
1–5 s fits inside the existing budget with room to spare. **The host's wake-up latency is a
constraint on the client's timeout, not a footnote.**

**Cold start is still visible once a day.** The first push after an hour of inactivity pays
1–5 s. Nothing waits on it — the LLM and the sync layer are both off every read path — so this
costs nothing a user can perceive.

---

## 2. Neon (needs Rajdweep)

- [ ] **Create a project** at neon.tech. Region: whichever is nearest — this is one user, and
      latency is irrelevant to a background push.
- [ ] **Copy the connection string.** It looks like
      `postgresql://<user>:<password>@<host>.neon.tech/<db>?sslmode=require`.
      **Keep `sslmode=require`** — it is not optional over the public internet, and `pgx`
      honours it.
- [ ] Store it in your password manager. It is a credential with write access to the backup.

No schema work is needed: `migrations.Apply` runs on every boot and is a no-op once applied,
so the first deploy creates the tables itself.

---

## 3. Generate the sync token (needs Rajdweep)

Not a password you invent — 32 random bytes:

```bash
openssl rand -base64 32
```

Store it in your password manager. **This is the credential that authorises a restore, so it
must survive the phone.** It goes in exactly two places: Koyeb's `SYNC_TOKEN`, and the app's
Settings → Backup → token field.

---

## 4. Koyeb (needs Rajdweep)

- [ ] Sign up, then **create a Web Service from the GitHub repo** `Rajdweep1/Expense-garden`,
      authorising Koyeb to read it.
- [ ] Build: **Dockerfile**, with build context `backend/core-api`.
- [ ] Instance: the **Free** type. Region: any.
- [ ] Port: **8080**.
- [ ] Environment variables — both as **secrets**, not plain env:
      - `DATABASE_URL` = the Neon string from §2
      - `SYNC_TOKEN` = the token from §3
- [ ] Health check: HTTP **`/v1/health`** on 8080.

> Use `/v1/health`, **not** `/v1/ready`. Health is liveness and never touches the database;
> ready pings Postgres. Pointing the platform's restart trigger at a database-dependent check
> turns a Neon blip into a container crash loop. `/v1/ready` is for you to curl when
> diagnosing, not for the platform to police.

---

## 5. Verify the deployment (I can do this once the URL exists)

```bash
BASE=https://<your-app>.koyeb.app
curl -s -w '\n%{http_code}\n' $BASE/v1/health          # {"ok":true} 200
curl -s -w '\n%{http_code}\n' $BASE/v1/ready           # {"ready":true} 200 — proves Neon is wired
curl -s -o /dev/null -w '%{http_code}\n' $BASE/v1/sync/snapshot          # 401, no token
curl -s -o /dev/null -w '%{http_code}\n' $BASE/v1/sync/snapshot \
  -H "Authorization: Bearer $TOKEN"                                      # 200
```

A `200` on `/v1/health` but `503` on `/v1/ready` means the container is up and `DATABASE_URL`
is wrong — the single most likely failure, and the reason the two checks are separate.

---

## 6. Point the phone at it

In the app: Settings → Backup → server url = `https://<your-app>.koyeb.app`, token = §3's
token, then Save.

> **`https` is now mandatory, not preferred.** `network_security_config.xml` is strict in the
> `main` source set, so a release build physically cannot talk to an `http://` URL. The debug
> override permits cleartext only to `10.0.2.2` / `localhost` / `127.0.0.1`. If a real host is
> ever entered with `http://`, it fails closed — which is the correct behaviour, and it will
> look like silence, so check the Settings status line first.

Then: log a transaction and confirm the status line reads "Backed up just now", and that the
row appears in Neon.

---

## 7. Then, and only then, the local server stops mattering

Until this is done, the backup protects against losing the **phone**, not against losing the
**Mac** — core-api and Postgres both live on it. That is the whole point of 2B, and it is
worth saying plainly because the app looks identical either way.

`pkill -f core-api` retires the local one. Note the trap from bring-up: `kill %1` kills the
`go run` wrapper and leaves the compiled child holding :8080.

---

## 8. Out of scope, deliberately

- **Backups of the replica** — Neon holds the only server-side copy, and nothing dumps it yet.
  That is 2C, and until it lands the honest description is "two copies, both live".
- **Sentry.** 2C.
- **Custom domain.** Costs money.
- **Staging.** One user, one environment.
