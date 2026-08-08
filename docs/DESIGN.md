# Design notes

Reasoning behind the decisions in this service, including the ones that were
wrong first.

---

## 1. Why the invariant lives in the database

The rule that defines double-entry bookkeeping is that a transaction's debits
equal its credits. The tempting place to enforce that is the service layer,
where the error message can be friendly.

That is the wrong place for it, because the service layer is not the only thing
that will ever write to this database. Over a real system's life there will be
a migration script, a support fix, a second service, a data patch applied at
2am during an incident. Every one of those bypasses the application. If the
invariant lives only in Java, it holds only for as long as Java is the only
writer — which is never true for long.

So it is a **deferred constraint trigger**:

```sql
CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();
```

`DEFERRABLE INITIALLY DEFERRED` is the load-bearing part. A non-deferred check
fires after the first leg is inserted, when the transfer is legitimately
half-written and unbalanced by definition. Deferring it to `COMMIT` means the
check sees the completed transaction and nothing else.

The application still validates the same rule before writing. Not for safety —
for the error message. The database's job is to make the bad state impossible;
the service's job is to return `422 unbalanced_transfer` with the imbalance in
it rather than letting a trigger surface as a 500.

**Cost:** the trigger re-queries the transfer's legs once per inserted row, so
an N-leg transfer runs the check N times. Transfers have two or three legs, so
this is irrelevant in practice. At a hundred legs it would be worth moving to a
statement-level trigger.

---

## 2. Signed amounts, and the sign convention

Every posting stores one signed `amount_minor`: **debit positive, credit
negative.**

The alternative — a `direction` column plus an unsigned amount — reads more
naturally and is worse in every way that matters. With signed amounts:

- "balanced" is `SUM(amount_minor) = 0`, which a trigger can check
- an account's balance is `SUM(amount_minor)`, no `CASE` expression
- the whole-ledger health check is `SUM(amount_minor) GROUP BY currency`,
  which must be zero and is one query

The cost is that a customer wallet holding £100 stores `-10000`, because a
wallet is a liability: the business owes the customer, and liabilities grow
with credits. `BalanceView` converts back at the edge using the account's
`normal_side`, and `MoneyAndBalanceTest` pins that conversion down in both
directions.

This is standard accounting, not a trick — but it is the single most confusing
thing in the codebase on first read, which is why it is documented on the table
itself.

---

## 3. Lock ordering

Two transfers, arriving together:

- A pays B
- B pays A

Lock each payer first and they deadlock: each transaction holds the row the
other needs. Postgres detects it after `deadlock_timeout` (1s by default) and
kills one — so the symptom is not corruption but a slow, intermittent 500 under
exactly the load where it hurts.

The fix is a global order over the contended resources. `BalanceRepository`
sorts account ids before locking:

```sql
WHERE b.account_id IN (:ids)
ORDER BY b.account_id
FOR UPDATE OF b
```

Every transaction in the system now reaches for those rows in the same
sequence, so one waits for the other instead of circling it. Cost: one sort of
two or three UUIDs.

`ConcurrentTransferTest` counts `CannotAcquireLockException` and asserts zero.
Removing the `ORDER BY` makes that assertion fail, which is the point of
writing it that way.

---

## 4. Idempotency, and a bug found by a test

A payments client that times out cannot distinguish a lost request from a lost
response, so it retries. Three cases:

| | Response |
|---|---|
| New key | Run the work, store the response |
| Same key, same body | Replay the stored response; move no money |
| Same key, **different** body | `409` — refuse both |

The third deserves comment. The client has reused a key across two logically
different payments. Executing the second is a duplicate payment; replaying the
first tells the caller that a payment they never made succeeded. There is no
correct answer, so the API refuses rather than picking one.

The winner is decided by `INSERT … ON CONFLICT (key) DO NOTHING`, so it is
settled by a unique index rather than a read-then-write that two concurrent
retries would both pass.

### The transaction structure, and what was wrong with the first version

The claim has to be **committed** before the work starts — inside the same
transaction it stays invisible to other sessions until commit, and both
attempts proceed. The obvious implementation is therefore an outer
`@Transactional` method with the claim nested as `REQUIRES_NEW`.

That version passed every sequential test and hung with twenty concurrent
retries. `IdempotencyConcurrencyTest` timed out on
`Failed to obtain JDBC Connection` after 10 seconds.

The cause was not the ledger at all — it was the **connection pool**. An outer
transaction holds its connection while the nested `REQUIRES_NEW` transaction
requests a second one. With a pool of ten and twenty callers, ten threads each
hold one connection and wait forever for a second that only another thread
could release. A textbook resource deadlock, and one that no amount of reading
the SQL would have revealed.

The fix was to stop nesting: three sequential transactions — claim, then
work-plus-record, then release-on-failure — so each caller holds exactly one
connection at a time and the pool merely queues.

The general lesson is worth more than the fix: `REQUIRES_NEW` is not free, and
its cost is denominated in pooled connections rather than in anything visible
in the query plan.

---

## 5. Two-phase transfers: intent versus record

`transfer_leg` holds what was asked for. `posting` holds what settled. Splitting
them is what makes an authorisation possible:

- **Pending** — legs written, funds reserved on `account_balance`, **no
  postings**. The money is spoken for and has not moved. That is precisely
  what an authorisation is.
- **Capture** — postings written, reservation released.
- **Void / expire** — reservation released, nothing posted and nothing
  reversed.

The alternative is to write postings up front and reverse them on void. That
makes every cancelled authorisation appear in the journal as a pair of
offsetting entries, which is noise in a document whose entire value is that it
records only what happened.

Capture deliberately does **not** re-check funds. The reservation already
excluded that money from everyone else's available balance, so a re-check could
only fail spuriously — and a capture that can fail for lack of funds defeats
the purpose of having authorised it.

Available balance is therefore `settled − reserved outflows`, and inbound
pending credits are deliberately excluded: treating unsettled incoming money as
spendable is how a ledger lets someone spend a payment that later fails.

---

## 6. The outbox

Events are inserted **in the transaction that made the change they describe**.
Either both commit or neither does. A relay publishes them afterwards and marks
them only once the consumer has acknowledged.

Delivery is therefore **at-least-once**, and consumers must deduplicate on
`(transferId, eventType)`. The alternative — mark first, then publish — is
at-most-once, and a reconciliation service that silently misses transfers is
worse than no reconciliation service at all: it reports a clean ledger while
money goes unaccounted for.

The relay claims work with `FOR UPDATE SKIP LOCKED`, so several instances can
drain the table concurrently without contending.

**Known trade-off:** the relay holds a row lock across an HTTP call. That is
inherent to claiming with `SKIP LOCKED` and marking in the same transaction. It
is bounded by a short client timeout and a batch limit, so a hung consumer
slows the relay rather than pinning rows indefinitely. At higher volume the
next step is to claim, commit, publish, and mark separately — accepting more
duplicate deliveries in exchange for not holding locks over the network.

---

## 7. Caching, and the cache being allowed to fail

Balance reads dominate a ledger's traffic; writes are rarer and always know
exactly which accounts they touched. That asymmetry makes caching worth doing
and invalidation exact rather than time-based.

Two decisions:

**Cache-aside, evict rather than update.** The write path deletes the key
instead of recomputing it. There is then only one place a balance is ever
computed, and therefore only one place it can be computed wrongly. Eviction
happens inside the write transaction — early, so a rollback leaves the cache
needlessly empty. That is the right direction to be wrong in: an unnecessary
miss costs one query, while a stale balance served after a successful transfer
is a wrong answer about someone's money.

**Every Redis call is failure-tolerant.** Reads fall through to Postgres, writes
and evictions are swallowed and counted. Redis is also removed from the
actuator health group, so a cache outage does not make the pod look unhealthy
and get it restarted. A cache that can take the system down with it has made
availability worse, not better.

See the README for what it actually measured. Briefly: the cache buys tail
latency, not headline latency, and quoting a bigger number than that would be
unsupportable.

---

## 8. Verification

`/v1/admin/verify` recomputes every balance from the postings and sums the
whole journal per currency. Both must be exactly zero-difference.

This is what makes the maintained balance safe to keep: the fast path is
trustworthy precisely because a slow path exists that can contradict it. It
returns 500 when the ledger does not balance, deliberately — that is an outage
and should page someone, not sit in a report nobody reads.

The concurrency tests run it after hammering the ledger, which turns "we
believe the locking is right" into "the balances still match the journal after
400 contended transfers."

---

## 9. Deliberately not built

- **Multi-currency FX.** The schema is ready for it — the balance trigger
  groups by currency, so each side of an FX transfer would have to balance on
  its own — but there is no rate handling, and inventing one without a real
  requirement would be guessing.
- **Authentication.** This is a service-to-service ledger; in production it
  sits behind a gateway. Adding a JWT filter would demonstrate nothing that
  the interesting parts of this codebase do not already.
- **Partitioning the posting table.** Correct at scale, pure ceremony here.
- **Event sourcing the whole thing.** The journal is already an append-only
  event log with a materialised projection beside it. Adding a framework on
  top would add vocabulary, not guarantees.
