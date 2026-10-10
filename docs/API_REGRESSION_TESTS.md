# API regression tests and outcomes

## Run
From the project root in PowerShell, with Docker Compose services healthy:

```powershell
.\api-tests\run-api-tests.ps1
```

The script reads tokens from `.env` without printing them, provisions a uniquely named test account, writes a CSV report to `api-tests/last-run-results.csv`, and exits non-zero if any check fails. It creates disposable test transactions and does not delete financial records.

## Scenarios recorded separately
1. Authenticated readiness endpoint returns `UP`.
2. Account provisioning succeeds.
3. A new credit is accepted.
4. Replaying the same request with the same transaction ID, idempotency key, amount, account, sequence, and event time does not change the balance a second time.
5. The same amount with a different transaction ID and idempotency key is accepted as a distinct transaction.
6. A posted transaction has exactly two ledger lines (line 1 and line 2).
7. Transaction listing contains unique transaction IDs.

## Interpretation / limitations
- The suite is an API smoke/regression suite against a running environment, not a substitute for concurrency stress tests or direct SQL integrity checks.
- Background processing is asynchronous; the script waits briefly before ledger checks. If a test fails due to a slow environment, inspect `docker compose logs --since 10m app1 app2` and the account/transaction status before rerunning.
- It does not automatically roll back test data because the service has no test-data deletion endpoint and ledger history should remain immutable.
- The backend currently enforces unique transaction ID, idempotency key, and `(account_id, sequence_number)`. Equal amounts are not unique identities. New transactions must use a fresh ID/key and next valid sequence.
