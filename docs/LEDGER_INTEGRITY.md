# Ledger integrity and duplicate diagnosis

## Current database protections
The PostgreSQL schema in `production-multinode/src/main/resources/db/migration/V001__initial_schema.sql` already declares:
- `PRIMARY KEY (transaction_id, line_number)` on `ledger_entries`, preventing a duplicate journal line for the same transaction and line number.
- A deferred constraint trigger requiring exactly two lines, one `CUSTOMER`, one `CLEARING`, one currency, and equal non-zero debit/credit totals.
- A trigger making ledger rows append-only; corrections must use a compensating transaction.
- A deferred trigger preventing a transaction from committing as `PROCESSED` unless exactly two ledger lines exist.

The intended two lines are not duplicate ledger records: they are the two sides of double-entry bookkeeping. A processed transaction should normally have two rows.

## Diagnostic SQL
Run against the PostgreSQL `transactions` database:

```sql
-- Transactions with an unexpected number of ledger rows (processed should have 2).
SELECT t.transaction_id, t.status, COUNT(l.line_number) AS ledger_lines
FROM transactions t
LEFT JOIN ledger_entries l ON l.transaction_id = t.transaction_id
GROUP BY t.transaction_id, t.status
HAVING (t.status = 'PROCESSED' AND COUNT(l.line_number) <> 2)
    OR (t.status <> 'PROCESSED' AND COUNT(l.line_number) > 0)
ORDER BY t.transaction_id;

-- Repeated business line identity. This should return no rows; the primary key also prevents it.
SELECT transaction_id, line_number, COUNT(*)
FROM ledger_entries
GROUP BY transaction_id, line_number
HAVING COUNT(*) > 1;

-- Detect a transaction journal whose total debits differ from total credits.
SELECT transaction_id, COUNT(*) AS line_count,
       SUM(debit_amount) AS total_debits, SUM(credit_amount) AS total_credits
FROM ledger_entries
GROUP BY transaction_id
HAVING COUNT(*) <> 2 OR SUM(debit_amount) <> SUM(credit_amount);

-- Account/sequence collisions. This should return no rows due to a unique constraint.
SELECT account_id, sequence_number, COUNT(*)
FROM transactions
GROUP BY account_id, sequence_number
HAVING COUNT(*) > 1;
```

Do not delete ledger rows to clean up a duplicate. First distinguish the expected two sides of one journal from multiple transaction IDs representing separate business requests. If historical rows are inconsistent, preserve evidence and correct balances through a reviewed compensating transaction or controlled repair migration.

## Repeated amount versus duplicate request
Same amount is allowed across distinct transactions. Uniqueness is based on transaction ID, idempotency key, and per-account sequence—not amount. A copied transaction must generate fresh `transactionId`, `requestId`, and `idempotencyKey`, and a new sequence number. An exact replay must return the original transaction and must not post a second journal.
