# Deploying

Both services, on permanently free infrastructure. About fifteen minutes.

| Piece | Where | Why that one |
|---|---|---|
| Postgres × 2 | [Neon](https://neon.tech) | Free tier is **permanent**. Render's free Postgres expires 30 days after creation and is then deleted with its data — useless for a link on a CV. |
| Redis | [Upstash](https://upstash.com) | Permanent free tier, 256MB. Optional: the ledger runs without it. |
| Both services | [Render](https://render.com) | Free Docker web services, no credit card. |

None of the three needs a card.

---

## 1. Two Postgres databases on Neon

Create **two projects** — `obol` and `accord`. They are separate services and
must not share a schema; the whole point of a reconciliation is that the two
records are independent.

For each, copy the connection string from the dashboard.

- For **accord**, take the `postgresql://…` string as-is. The service rewrites
  the scheme to name the psycopg driver at startup, so it can be pasted
  unedited.
- For **obol**, switch the dashboard snippet to **Java / JDBC**. You want the
  `jdbc:postgresql://…` form plus the username and password, which go in as
  three separate variables.

## 2. Redis on Upstash (optional)

Create a database, copy the `rediss://…` URL. Note the double `s` — Upstash is
TLS-only, and a `redis://` URL will fail to connect.

Skip this and the ledger still works: balances are read straight from Postgres
and a warning is logged. The cache is an optimisation, and the code is written
so that stays true.

## 3. accord-recon on Render

New → Blueprint → connect `accord-recon`. The `render.yaml` is picked up
automatically. Fill in the one variable it asks for:

```
ACCORD_DATABASE_URL   = <Neon accord connection string>
```

Render generates `ACCORD_WEBHOOK_SECRET` for you. **Copy its value** from the
service's Environment tab — the ledger needs it next.

## 4. obol-ledger on Render

New → Blueprint → connect `obol-ledger`. Fill in:

```
DATABASE_URL       = jdbc:postgresql://<neon-obol-host>/obol?sslmode=require
DATABASE_USER      = <neon user>
DATABASE_PASSWORD  = <neon password>
REDIS_URL          = rediss://…            (omit if you skipped Upstash)
OUTBOX_TARGET_URL  = https://accord-recon.onrender.com/v1/ledger/events
OUTBOX_SIGNATURE   = <the ACCORD_WEBHOOK_SECRET you copied>
```

Flyway migrates on first boot; Alembic does the same for accord. Neither needs
a separate release step at this size.

## 5. Prove it works

```bash
curl https://obol-ledger.onrender.com/actuator/health
curl https://accord-recon.onrender.com/health
curl https://obol-ledger.onrender.com/v1/admin/verify     # must be healthy: true

./scripts/demo.sh https://obol-ledger.onrender.com        # 25 assertions
```

Then, from the accord repo, drive both ends:

```bash
./scripts/demo.py \
  --ledger https://obol-ledger.onrender.com \
  --accord https://accord-recon.onrender.com
```

That settles real payments, lets the outbox relay carry them across, injects
faults into a generated statement, reconciles, and checks every injected fault
was reported.

---

## What free costs you

**Instances sleep after 15 minutes idle.** The first request wakes them, and a
JVM cold-starting on a shared vCPU takes roughly 40–60 seconds. Nothing is
broken; it is asleep.

Both READMEs should say so plainly next to the live link — a recruiter who
clicks and waits without explanation concludes the thing is down, which is a
worse outcome than the honest note. Keeping one service permanently awake
would consume the entire 750 instance-hours a month the free plan allows, so
it is not a trade worth making for two services.

**Neon scales its compute to zero too**, which adds a second or so to the first
query. The free tier allows 100 compute-hours per project per month, which a
portfolio project will not come close to.

**Upstash allows 500,000 commands a month.** The balance cache uses one read
per balance request and one delete per transfer.

---

## Re-measuring the cache

The benchmark numbers in the README were taken with Postgres and Redis on the
same machine, where the database is already fast and the cache only improves
the tail. Deployed, the database is a network hop away and Redis is a nearer
one, so the gap should widen.

```bash
./scripts/benchmark.py https://obol-ledger.onrender.com
```

Run it **after** waking the instance, and quote whatever it says. A number
measured on the deployment is the only one worth putting on a CV.
