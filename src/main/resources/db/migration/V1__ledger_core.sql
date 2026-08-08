-- obol-ledger core schema.
--
-- Sign convention: every posting carries a SIGNED amount in minor units.
--   debit  -> positive
--   credit -> negative
-- A transfer is balanced when its postings sum to zero within each currency.
-- That is not a convention the application is trusted to honour; it is a
-- deferred constraint trigger below, so an unbalanced transfer cannot commit
-- even if the service is buggy, raced, or bypassed entirely by psql.

-- ---------------------------------------------------------------- accounts

CREATE TABLE ledger_account (
    id              UUID        PRIMARY KEY,
    code            TEXT        NOT NULL UNIQUE,
    name            TEXT        NOT NULL,
    currency        CHAR(3)     NOT NULL,
    account_type    TEXT        NOT NULL,
    -- 'D' for debit-normal (assets, expenses), 'C' for credit-normal
    -- (liabilities, equity, revenue). Drives how a signed balance is
    -- presented, and which direction counts as an outflow.
    normal_side     CHAR(1)     NOT NULL,
    -- When false the ledger refuses any transfer that would drive the
    -- account's available balance below zero. Cash-in and revenue accounts
    -- set this true; customer wallets do not.
    allow_negative  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_account_currency_ck
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ledger_account_type_ck
        CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    CONSTRAINT ledger_account_normal_side_ck
        CHECK (normal_side IN ('D','C'))
);

-- Balance is a materialised projection of `posting`, not a second source of
-- truth. V1__ships an admin endpoint that recomputes it from the postings and
-- reports any drift; the invariant is that the drift is always zero.
CREATE TABLE account_balance (
    account_id             UUID        PRIMARY KEY REFERENCES ledger_account(id),
    -- signed sum of all SETTLED postings for this account
    posted_minor           BIGINT      NOT NULL DEFAULT 0,
    -- reservations held by PENDING transfers, both always >= 0
    pending_debits_minor   BIGINT      NOT NULL DEFAULT 0,
    pending_credits_minor  BIGINT      NOT NULL DEFAULT 0,
    -- optimistic-lock counter; the write path also takes a row lock, this
    -- catches anything that reads a balance and writes it back without one
    version                BIGINT      NOT NULL DEFAULT 0,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT account_balance_pending_debits_ck  CHECK (pending_debits_minor  >= 0),
    CONSTRAINT account_balance_pending_credits_ck CHECK (pending_credits_minor >= 0)
);

-- --------------------------------------------------------------- transfers

CREATE TABLE transfer (
    id                  UUID        PRIMARY KEY,
    -- caller's own reference, e.g. a PSP payment id. Reconciliation matches
    -- on this, so it is indexed but deliberately not unique: a retried
    -- payment can legitimately produce a second transfer.
    external_id         TEXT,
    state               TEXT        NOT NULL,
    currency            CHAR(3)     NOT NULL,
    -- sum of the debit legs; a reporting convenience, the legs are the truth
    amount_minor        BIGINT      NOT NULL,
    description         TEXT,
    pending_expires_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at           TIMESTAMPTZ,
    voided_at           TIMESTAMPTZ,

    CONSTRAINT transfer_state_ck
        CHECK (state IN ('PENDING','POSTED','VOIDED','EXPIRED')),
    CONSTRAINT transfer_amount_ck
        CHECK (amount_minor > 0),
    -- Each state pins down exactly which timestamps may be set. A pending
    -- transfer must carry an expiry, a settled one must not still be waiting,
    -- and nothing can be both posted and voided. EXPIRED is a void the
    -- sweeper performed rather than a caller, so it records voided_at too.
    CONSTRAINT transfer_state_timestamps_ck CHECK (
        (state = 'PENDING'
            AND pending_expires_at IS NOT NULL AND posted_at IS NULL AND voided_at IS NULL)
     OR (state = 'POSTED'
            AND posted_at IS NOT NULL AND voided_at IS NULL)
     OR (state IN ('VOIDED','EXPIRED')
            AND voided_at IS NOT NULL AND posted_at IS NULL)
    )
);

CREATE INDEX transfer_external_id_idx ON transfer (external_id) WHERE external_id IS NOT NULL;
CREATE INDEX transfer_created_at_idx  ON transfer (created_at DESC);
-- drives the expiry sweeper: only ever scans transfers still awaiting capture
CREATE INDEX transfer_pending_expiry_idx
    ON transfer (pending_expires_at) WHERE state = 'PENDING';

-- ------------------------------------------------------------------- legs

-- The instruction. A transfer's legs are recorded when it is created, whether
-- it settles immediately or sits PENDING awaiting capture; `posting` below is
-- the journal of what actually settled. Keeping the two apart is what lets a
-- pending authorisation reserve funds without ever touching the journal, and
-- means a voided transfer leaves an auditable record of what was intended
-- rather than no record at all.
CREATE TABLE transfer_leg (
    transfer_id  UUID    NOT NULL REFERENCES transfer(id),
    seq          INT     NOT NULL,
    account_id   UUID    NOT NULL REFERENCES ledger_account(id),
    currency     CHAR(3) NOT NULL,
    -- signed, same convention as posting: debit positive, credit negative
    amount_minor BIGINT  NOT NULL,

    PRIMARY KEY (transfer_id, seq),
    CONSTRAINT transfer_leg_amount_nonzero_ck CHECK (amount_minor <> 0)
);

CREATE INDEX transfer_leg_account_idx ON transfer_leg (account_id);

-- ---------------------------------------------------------------- postings

CREATE TABLE posting (
    id                  BIGSERIAL   PRIMARY KEY,
    transfer_id         UUID        NOT NULL REFERENCES transfer(id),
    account_id          UUID        NOT NULL REFERENCES ledger_account(id),
    currency            CHAR(3)     NOT NULL,
    -- signed: debit positive, credit negative. Never zero -- a zero-value
    -- leg is a bug in the caller, not a no-op worth persisting.
    amount_minor        BIGINT      NOT NULL,
    -- leg ordinal within the transfer, stable across replays
    seq                 INT         NOT NULL,
    -- the account's signed posted balance immediately after this posting was
    -- applied. Written under the same row lock that updates account_balance,
    -- so a statement can be rendered without re-summing history.
    balance_after_minor BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT posting_amount_nonzero_ck CHECK (amount_minor <> 0),
    CONSTRAINT posting_seq_unique UNIQUE (transfer_id, seq)
);

-- The account statement query: postings for one account, newest first.
CREATE INDEX posting_account_created_idx ON posting (account_id, id DESC);
CREATE INDEX posting_transfer_idx        ON posting (transfer_id);

-- ------------------------------------------------- the double-entry guard

-- One function guards both tables. It reads its own table name from
-- TG_TABLE_NAME so the intent (transfer_leg) and the record (posting) are held
-- to an identical standard, and there is only one copy of the rule to keep
-- correct.
CREATE OR REPLACE FUNCTION assert_transfer_balanced() RETURNS TRIGGER AS $$
DECLARE
    unbalanced_currency TEXT;
    imbalance           BIGINT;
BEGIN
    -- Grouped by currency rather than checked as a single sum, so the rule
    -- still holds if an FX transfer ever carries legs in two currencies:
    -- each side must balance on its own.
    EXECUTE format(
        'SELECT currency, SUM(amount_minor)
           FROM %I
          WHERE transfer_id = $1
          GROUP BY currency
         HAVING SUM(amount_minor) <> 0
          LIMIT 1', TG_TABLE_NAME)
      INTO unbalanced_currency, imbalance
     USING NEW.transfer_id;

    IF unbalanced_currency IS NOT NULL THEN
        RAISE EXCEPTION
            'transfer % is unbalanced in % (%): debits minus credits = %',
            NEW.transfer_id, unbalanced_currency, TG_TABLE_NAME, imbalance
            USING ERRCODE = '23514';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- DEFERRABLE INITIALLY DEFERRED is the whole point: the check runs at COMMIT,
-- once every leg is in place, rather than after the first INSERT when the
-- transfer is legitimately half-written.
CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();

CREATE CONSTRAINT TRIGGER transfer_leg_balanced
    AFTER INSERT ON transfer_leg
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();

-- Postings are append-only. A mistake is corrected by a reversing transfer
-- that is itself visible in history, never by editing what was recorded --
-- the same rule an auditor would apply to a paper journal.
CREATE OR REPLACE FUNCTION reject_posting_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'posting rows are immutable; attempted % (correct with a reversing transfer)',
        TG_OP
        USING ERRCODE = '0A000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER posting_immutable
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION reject_posting_mutation();

-- ------------------------------------------------------------ idempotency

CREATE TABLE idempotency_key (
    key             TEXT        PRIMARY KEY,
    -- SHA-256 of the canonicalised request body. Same key + same body is a
    -- retry and replays the stored response; same key + different body is a
    -- client bug and gets a 409.
    request_hash    TEXT        NOT NULL,
    state           TEXT        NOT NULL,
    response_status INT,
    response_body   TEXT,
    transfer_id     UUID        REFERENCES transfer(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT idempotency_state_ck CHECK (state IN ('IN_FLIGHT','COMPLETED'))
);

CREATE INDEX idempotency_expiry_idx ON idempotency_key (expires_at);

-- ----------------------------------------------------------------- outbox

-- Transactional outbox. Events are written in the same transaction as the
-- postings they describe, so the ledger and the event stream cannot disagree:
-- either both committed or neither did. A relay publishes them afterwards.
CREATE TABLE outbox_event (
    id             BIGSERIAL   PRIMARY KEY,
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT         NOT NULL DEFAULT 0,
    last_error     TEXT
);

-- Partial index: the relay only ever asks for unpublished rows, so the index
-- stays small no matter how much history accumulates.
CREATE INDEX outbox_unpublished_idx
    ON outbox_event (id) WHERE published_at IS NULL;
