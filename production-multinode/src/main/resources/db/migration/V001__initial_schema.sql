-- PostgreSQL is the source of truth. Constraints are the final guard against cross-instance races.
CREATE TABLE IF NOT EXISTS accounts (
    account_id VARCHAR(80) PRIMARY KEY,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    last_consumed_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_consumed_sequence >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS transactions (
    transaction_id VARCHAR(80) PRIMARY KEY,
    request_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    request_hash CHAR(64) NOT NULL,
    account_id VARCHAR(80) NOT NULL REFERENCES accounts(account_id),
    transaction_type VARCHAR(16) NOT NULL CHECK (transaction_type IN ('CREDIT','DEBIT','REVERSAL')),
    original_transaction_id VARCHAR(80),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    sequence_number BIGINT NOT NULL CHECK (sequence_number > 0),
    event_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED','PROCESSING','PROCESSED','PENDING','FAILED','RETRY_PENDING','DEAD_LETTER')),
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    failure_reason VARCHAR(500),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    simulated_failures_remaining INTEGER NOT NULL DEFAULT 0 CHECK (simulated_failures_remaining >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, sequence_number),
    CHECK ((transaction_type = 'REVERSAL' AND original_transaction_id IS NOT NULL) OR
           (transaction_type <> 'REVERSAL' AND original_transaction_id IS NULL))
);
-- Only one full reversal per original posting in this scoped implementation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_transaction_single_reversal
    ON transactions(original_transaction_id) WHERE transaction_type = 'REVERSAL' AND status <> 'FAILED';
CREATE INDEX IF NOT EXISTS idx_transactions_worker
    ON transactions(status, next_attempt_at, created_at, transaction_id);
CREATE INDEX IF NOT EXISTS idx_transactions_account_status
    ON transactions(account_id, status, sequence_number);

-- Double-entry journal. Every posted transaction has two lines with equal total debits and credits.
-- The application inserts both lines in the same DB transaction as the balance/state update.
CREATE TABLE IF NOT EXISTS ledger_entries (
    transaction_id VARCHAR(80) NOT NULL REFERENCES transactions(transaction_id),
    line_number SMALLINT NOT NULL CHECK (line_number IN (1,2)),
    account_id VARCHAR(100) NOT NULL,
    account_role VARCHAR(16) NOT NULL CHECK (account_role IN ('CUSTOMER','CLEARING')),
    debit_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (debit_amount >= 0),
    credit_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (credit_amount >= 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    posted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (transaction_id, line_number),
    CHECK ((debit_amount > 0 AND credit_amount = 0) OR (credit_amount > 0 AND debit_amount = 0))
);
CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries(account_id, posted_at);

-- Durable outbox: any future downstream publication must use this table and idempotent consumers.
CREATE TABLE IF NOT EXISTS outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (aggregate_id, event_type)
);
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox_events(next_attempt_at, created_at) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS processing_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    transaction_id VARCHAR(80),
    event_type VARCHAR(80) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_transaction ON processing_audit(transaction_id, created_at);

-- Reject a transaction if its posted journal is not balanced. The deferred trigger runs at commit,
-- after both lines have been inserted, so an incomplete journal cannot commit.
CREATE OR REPLACE FUNCTION assert_transaction_journal_balanced() RETURNS trigger AS $$
DECLARE
    total_debits NUMERIC(19,4);
    total_credits NUMERIC(19,4);
    line_count INTEGER;
    customer_lines INTEGER;
    clearing_lines INTEGER;
    currency_count INTEGER;
BEGIN
    SELECT COALESCE(SUM(debit_amount), 0), COALESCE(SUM(credit_amount), 0), COUNT(*),
           COUNT(*) FILTER (WHERE account_role = 'CUSTOMER'),
           COUNT(*) FILTER (WHERE account_role = 'CLEARING'),
           COUNT(DISTINCT currency)
      INTO total_debits, total_credits, line_count, customer_lines, clearing_lines, currency_count
      FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF line_count <> 2 OR customer_lines <> 1 OR clearing_lines <> 1 OR currency_count <> 1
       OR total_debits <> total_credits OR total_debits <= 0 THEN
        RAISE EXCEPTION 'Unbalanced journal for transaction %', NEW.transaction_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_journal_balanced ON ledger_entries;
CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT OR UPDATE ON ledger_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transaction_journal_balanced();

-- Posted journal lines are append-only. Corrections must be represented by new compensating transactions.
CREATE OR REPLACE FUNCTION prevent_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are immutable; post a compensating transaction instead'
        USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_ledger_immutable ON ledger_entries;
CREATE TRIGGER trg_ledger_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();

-- A transaction cannot become PROCESSED unless its complete double-entry journal exists at commit.
CREATE OR REPLACE FUNCTION assert_processed_transaction_has_journal() RETURNS trigger AS $$
DECLARE
    journal_lines INTEGER;
BEGIN
    IF NEW.status = 'PROCESSED' THEN
        SELECT COUNT(*) INTO journal_lines FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
        IF journal_lines <> 2 THEN
            RAISE EXCEPTION 'Processed transaction % must have exactly two journal lines', NEW.transaction_id
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_processed_has_journal ON transactions;
CREATE CONSTRAINT TRIGGER trg_processed_has_journal
    AFTER UPDATE ON transactions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_processed_transaction_has_journal();
