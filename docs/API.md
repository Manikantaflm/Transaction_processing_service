# HTTP API contract

Base URL in the local Compose environment: `http://localhost:8080`.

All `/v1` endpoints require a bearer token. Use `API_BEARER_TOKEN` for transaction submission. Use the separate `OPS_BEARER_TOKEN` for account provisioning/reads, transaction and ledger inspection, summary, readiness, and redrive. The local shared-token mechanism is a development/assessment control, not a substitute for production OAuth2/OIDC and role-based authorization.

## Endpoints

| Method | Path | Credential | Success | Purpose |
|---|---|---|---|---|
| GET | `/` | None | `200 text/html` | Static operations landing page; no transaction data |
| GET | `/health/live` | None | `200` | Process liveness only; no dependency check |
| GET | `/health/ready` | Operations | `200` / `503` | Database readiness and summary |
| POST | `/v1/accounts` | Operations | `201` | Provision account and currency; initial balance is zero |
| GET | `/v1/accounts/{accountId}` | Operations | `200` / `404` | Balance and consumed sequence cursor |
| POST | `/v1/transactions` | API | `202` | Durably accept one transaction for asynchronous processing |
| POST | `/v1/transactions/batch` | API | `207` | Accept up to 500 records; return a per-record outcome |
| GET | `/v1/transactions?limit=100` | Operations | `200` | List latest transaction records; limit 1–500 |
| GET | `/v1/transactions/{id}/ledger` | Operations | `200` | Immutable journal lines for one transaction |
| GET | `/v1/summary` | Operations | `200` | Status counts, duplicate submissions, journal counts |
| POST | `/v1/transactions/{id}/redrive` | Operations | `202` / `409` | Redrive a `DEAD_LETTER` transaction after operator review |

The `202` response means the request was accepted durably, not that it has already posted to the ledger. Query the transaction list and account to observe completion.

## Account creation

```http
POST /v1/accounts
Authorization: Bearer <OPS_BEARER_TOKEN>
Content-Type: application/json

{"accountId":"ACC-201","currency":"USD"}
```

Account IDs are 1–80 characters from `[A-Za-z0-9][A-Za-z0-9._:-]*`. Currency must be recognized by the JDK ISO 4217 currency data. Re-provisioning the same account and currency is idempotent; attempting to reuse an account ID with another currency returns `409`.

## Transaction request

```json
{
  "transactionId": "TXN-2001",
  "requestId": "REQ-2001",
  "idempotencyKey": "KEY-2001",
  "accountId": "ACC-201",
  "transactionType": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "sequenceNumber": 1,
  "eventTime": "2026-10-10T08:00:00Z"
}
```

Required fields: `transactionId`, `requestId`, `idempotencyKey`, `accountId`, `transactionType`, `amount`, `currency`, `sequenceNumber`, and `eventTime`.

- `transactionType`: `CREDIT`, `DEBIT`, or `REVERSAL`.
- Amount must be positive, fit PostgreSQL `NUMERIC(19,4)`, and respect the currency minor-unit precision. Negative amounts are rejected at ingestion; represent debits using `transactionType: DEBIT` and a positive amount.
- `sequenceNumber` is a positive integer and is monotonic per account. Sequences begin at 1.
- `eventTime` must be an ISO-8601 timestamp parseable by `Instant.parse`, normally with a `Z` UTC suffix. It is metadata; the sequence controls ordering.
- `originalTransactionId` is mandatory only for `REVERSAL`. The current scope permits a full reversal only after the original CREDIT/DEBIT has been processed, for the same account/currency and exact original amount. The reference is validated when the reversal sequence becomes eligible, so the request may arrive before its original event if account sequence ordering leaves it pending. A full original transaction can be reversed at most once.
- `transientFailuresToSimulate` is an optional test-only integer from 0–10. It is rejected unless fault injection is enabled outside `APP_ENV=production`. Do not enable this in a real environment.

## Idempotency behavior

The transaction ID and idempotency key are separate unique database constraints. The canonical payload hash covers the business transaction and excludes delivery-only `requestId` and `idempotencyKey` fields.

- Exact replay of a transaction ID and canonical payload returns the existing transaction with `duplicate: true`; it does not change balance or journal.
- Reusing an idempotency key for another transaction, or reusing a transaction ID with a different business payload, returns `409 Conflict`.
- A duplicate submission is counted in the audit trail. It does not replace the original persisted transaction status with `DUPLICATE`.

## Batch ingestion

`POST /v1/transactions/batch` accepts a JSON array of up to 500 transaction objects. Each item is independently validated and submitted. Response example:

```json
{
  "count": 2,
  "outcomes": [
    {"index": 0, "transactionId": "TXN-1", "outcome": "ACCEPTED", "result": {"transactionId": "TXN-1", "status": "RECEIVED", "duplicate": false, "sequenceNumber": 1}},
    {"index": 1, "transactionId": "TXN-1", "outcome": "DUPLICATE", "result": {"transactionId": "TXN-1", "duplicate": true}}
  ]
}
```

Possible item outcomes: `ACCEPTED`, `DUPLICATE`, `REJECTED`, and `RETRYABLE_ERROR`. Invalid individual items do not abort unrelated valid items. Malformed batch JSON, a non-array body, a body larger than 1 MiB, or more than 500 records is rejected at the request level.

## Status lifecycle

| Status | Meaning |
|---|---|
| `RECEIVED` | Durably accepted and awaiting worker processing |
| `PROCESSING` | Internal in-transaction state; usually not visible as committed state |
| `PENDING` | A required earlier account sequence is missing |
| `RETRY_PENDING` | Eligible for retry after `next_attempt_at` |
| `PROCESSED` | Ledger, balance, status, audit, and outbox committed together |
| `FAILED` | Permanent business rejection; no ledger posting; its expected sequence is consumed after evaluation |
| `DEAD_LETTER` | Infrastructure retry exhaustion or unexpected processing failure requiring investigation |

## Error contract

- `400`: malformed JSON, missing field, invalid type/currency/amount/sequence/time, wrong request shape, or body limit violation.
- `401`: missing or invalid bearer token.
- `404`: unknown route or account.
- `409`: conflicting idempotency identity, account currency mismatch during provisioning, sequence uniqueness collision, or invalid redrive state.
- `503`: persistence failure on single-transaction submission; retry with the same idempotency key.
- `207`: batch accepted for per-record processing; inspect each outcome.

Error responses do not expose stack traces or SQL details. Operational logs should avoid logging authorization headers, tokens, or full request bodies.
