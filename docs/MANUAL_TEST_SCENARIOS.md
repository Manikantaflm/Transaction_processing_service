# Manual test scenarios — PostgreSQL multi-instance profile

**Environment:** start the Docker Compose profile with two app nodes and a shared PostgreSQL database. Use unique account/transaction IDs per test or reset the local database volume between test runs. Use the API token for transaction submission and the operations token for provisioning/inspection. Never run fault injection against production.

| ID | Priority | Scenario / steps | Expected result |
|---|---|---|---|
| MT-001 | P1 | Provision a new USD account | HTTP 201; zero balance and sequence cursor 0 |
| MT-002 | P2 | Provision the same account again with the same currency | Idempotent response; no second account |
| MT-003 | P1 | Reuse an account ID with another currency | HTTP 409; original currency unchanged |
| MT-004 | P1 | Submit CREDIT 100.00 at sequence 1 | HTTP 202; eventually PROCESSED; balance 100.00 |
| MT-005 | P1 | Submit DEBIT 25.00 at next sequence | Eventually PROCESSED; balance 75.00 |
| MT-006 | P1 | Debit more than available balance | FAILED; no journal lines; balance unchanged; sequence consumed |
| MT-007 | P0 | Replay identical transaction ID and payload | Duplicate outcome; original status and balance unchanged; no second journal |
| MT-008 | P0 | Reuse transaction ID with changed amount | HTTP 409; no financial effect |
| MT-009 | P0 | Reuse transaction ID with changed account | HTTP 409; no financial effect |
| MT-010 | P1 | Replay same transaction ID/payload with a new requestId | Duplicate outcome; original record preserved |
| MT-011 | P0 | Reuse an idempotency key for a different transaction ID | HTTP 409, even if amount/account happen to match |
| MT-012 | P1 | Batch contains a transaction and its duplicate later in the same array | First accepted; later item DUPLICATE; one posting only |
| MT-013 | P1 | Batch has valid, malformed, and duplicate records | Per-item outcomes; one invalid item does not abort valid siblings |
| MT-014 | P2 | Submit exactly 500 records in one valid batch | Accepted for per-item handling; all outcomes present |
| MT-015 | P1 | Submit 501 records | HTTP 400; no batch item processed |
| MT-016 | P1 | Submit body larger than 1 MiB | HTTP 400; no transaction accepted |
| MT-017 | P1 | Submit malformed JSON | HTTP 400 with safe error response |
| MT-018 | P1 | Submit JSON with duplicate object keys | HTTP 400; parser rejects ambiguous input |
| MT-019 | P1 | Submit amount 0 | HTTP 400; no row created |
| MT-020 | P1 | Submit negative amount | HTTP 400; caller must use positive amount and DEBIT type |
| MT-021 | P1 | Submit USD amount with 3 meaningful fractional digits | HTTP 400; no row created |
| MT-022 | P1 | Submit JPY amount with fractional units | HTTP 400; JPY minor-unit rule enforced |
| MT-023 | P2 | Submit KWD amount with 3 fractional digits | Accepted if otherwise valid |
| MT-024 | P1 | Submit amount exceeding NUMERIC(19,4) bounds | HTTP 400; no database overflow response leaked |
| MT-025 | P1 | Submit unknown currency code | HTTP 400 |
| MT-026 | P1 | Submit transaction currency different from account currency | When its sequence is evaluated, FAILED; no journal; sequence cursor advances |
| MT-027 | P0 | Submit account sequence 2 before sequence 1 | Sequence 2 becomes PENDING; no balance change |
| MT-028 | P0 | Submit missing sequence 1 after MT-027 | Sequence 1 processes; sequence 2 becomes eligible and drains |
| MT-029 | P1 | Submit sequence 3 while sequence 1 is missing | Remains PENDING; no automatic sequence skipping |
| MT-030 | P1 | Submit another transaction for an already reserved account sequence | HTTP 409; existing record preserved |
| MT-031 | P0 | Submit the same transaction concurrently through both app nodes | One durable transaction; one journal; all equivalent retries return the existing outcome |
| MT-032 | P0 | Submit different transaction IDs concurrently for the same account sequence | One reservation wins; the other receives conflict; no duplicate sequence |
| MT-033 | P0 | Process adjacent account sequences with both worker instances active | Account row lock serializes balance/cursor changes; final balance matches sequence order |
| MT-034 | P1 | Restart both application containers while retaining the PostgreSQL volume | Accepted records, statuses, balances, journal, and retry times persist |
| MT-035 | P1 | Kill an app container during a database transaction | PostgreSQL rolls back uncommitted financial effects; worker recovers durable work |
| MT-036 | P1 | Enable test-only fault injection for one transient failure | RETRY_PENDING then PROCESSED; one balanced journal only |
| MT-037 | P1 | Simulate failures through retry exhaustion | DEAD_LETTER; no financial posting before successful processing |
| MT-038 | P1 | Redrive a DEAD_LETTER record with operations credential | Requeued with retry state reset; eventual processing is still idempotent |
| MT-039 | P1 | Redrive a PROCESSED transaction | HTTP 409; ledger and balance unchanged |
| MT-040 | P1 | Cause an unexpected database processing error in a controlled test | Transaction is recoverable/dead-lettered; no partial ledger/balance commit |
| MT-041 | P1 | Reverse a processed CREDIT with matching account/currency/amount | Compensating transaction posts; customer balance is reversed if funds permit |
| MT-042 | P1 | Submit reversal before original is processed | Reversal does not post; becomes FAILED when its sequence is evaluated |
| MT-043 | P1 | Reverse with an amount different from the original | FAILED; no journal; sequence cursor advances |
| MT-044 | P1 | Reverse an original from another account or currency | FAILED; no journal |
| MT-045 | P0 | Submit two full reversals for one original transaction | Only one can be accepted for posting; no duplicate reversal effect |
| MT-046 | P1 | Reverse a CREDIT after the funds have been spent and balance would go negative | FAILED; no journal; balance remains non-negative |
| MT-047 | P0 | Inspect a successful transaction's ledger endpoint | Exactly two journal lines: customer and clearing |
| MT-048 | P0 | Sum debit and credit amounts for each successful transaction | Debits equal credits exactly for each currency |
| MT-049 | P0 | Attempt UPDATE on a posted ledger line in test DB | Database trigger rejects mutation |
| MT-050 | P0 | Attempt DELETE on a posted ledger line in test DB | Database trigger rejects deletion |
| MT-051 | P1 | Inspect outbox after successful posting | One outbox event committed with transaction and journal |
| MT-052 | P1 | Inspect outbox after rejected transaction | No successful-posting outbox event |
| MT-053 | P1 | Submit API request without Authorization | HTTP 401 |
| MT-054 | P1 | Submit API request with invalid API token | HTTP 401; no state change |
| MT-055 | P1 | Use operations token to submit transaction | HTTP 401; privilege separation is enforced |
| MT-056 | P1 | Use API token to read account balance or summary | HTTP 401 |
| MT-057 | P1 | Request readiness without operations token | HTTP 401; operational data is not exposed |
| MT-058 | P2 | Request liveness without token | HTTP 200 with only process liveness status |
| MT-059 | P2 | Request unknown route | HTTP 404; no stack trace |
| MT-060 | P1 | Inspect responses and logs for invalid requests | No bearer tokens, full request bodies, or SQL exception details are exposed |
| MT-061 | P2 | Send a burst exceeding configured Nginx API rate | Rate limiter returns 429/limit response; normal traffic resumes after window |
| MT-062 | P1 | Run worker contention with a large pending backlog | Workers do not claim the same locked transaction; unrelated accounts progress concurrently |
| MT-063 | P1 | Start both app replicas against a fresh database simultaneously | Flyway migration locking results in one valid schema, with no partial migration |
| MT-064 | P1 | Restart PostgreSQL in a controlled test environment | Readiness fails while DB is down; accepted committed work resumes after recovery |
| MT-065 | P2 | Open the service landing page in Chrome | Page title, heading, health link, and authentication notice are visible |
| MT-066 | P2 | Run Selenium/Cucumber/TestNG suite against local Compose stack | Scenarios pass and HTML/JSON reports are generated |

## Test evidence expectations

For concurrency and recovery tests, record the request IDs, transaction IDs, response codes, final account balance, transaction state, journal line count, and outbox count. A green HTTP response alone is not evidence that financial invariants hold. Use the PostgreSQL integration suite for MT-007 through MT-008, MT-027 through MT-033, MT-041 through MT-050, and fault recovery scenarios wherever practical.
