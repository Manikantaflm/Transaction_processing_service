# Requirement traceability

The original FR IDs refer to the uploaded FDE L2 assessment. Production extensions are identified separately so they are not mistaken for requirements stated in the source document.

| Requirement | Source intent | Implementation | Verification |
|---|---|---|---|
| FR-01 | Replaceable/configurable input source | HTTP single-transaction and batch adapters; DB-backed accepted records | API/manual batch tests |
| FR-02 | Reject malformed records | Strict JSON parser, field/amount/currency/sequence/time validation | Demo validation suite; PostgreSQL/API tests |
| FR-03 | Maintain processing state | `transactions` PostgreSQL table | Testcontainers persistence/restart tests |
| FR-04 | State lifecycle | `RECEIVED`, `PROCESSING`, `PENDING`, `PROCESSED`, `FAILED`, `RETRY_PENDING`, `DEAD_LETTER`; duplicate is submission outcome | State/ordering/retry tests |
| FR-05 | Each unique business transaction once | Unique transaction ID/idempotency key, canonical payload hash, atomic journal | Duplicate and concurrent cross-instance tests |
| FR-06 | Later duplicates in a batch | `POST /v1/transactions/batch`, per-item outcomes | Batch integration/manual tests |
| FR-07 | Out-of-order strategy | Per-account `last_consumed_sequence`, durable `PENDING` records | Gap/drain tests |
| FR-08 | Simulate transient failure | Feature-flagged `transientFailuresToSimulate` field | Retry integration test; disabled by default |
| FR-09 | Retry without duplicate effects | Durable retry count/time, rollback on failure, journal uniqueness, atomic posting | Retry/recovery tests |
| FR-10 | Separate business logic/input | `ProductionApplication` adapter separated from `PgTransactionService` | Architecture review |
| FR-11 | Summary counts | `/v1/summary` and authenticated readiness | API/manual summary checks |
| FR-12 | Critical unit tests | JDK-only core suite plus JUnit/Testcontainers suite | `verify-local` and `verify-production` |
| NFR-01 | Multi-instance safety (extension) | PostgreSQL uniqueness, `SKIP LOCKED`, account row lock | Cross-service-instance Testcontainers tests |
| NFR-02 | Double-entry accounting (extension) | Two journal lines and deferred balance trigger | Journal/reversal/immutability tests |
| NFR-03 | Security (extension) | Separate API/ops bearer credentials, body limits, safe errors, Nginx rate limit | Auth/manual tests; security review still required |
| NFR-04 | Recovery (extension) | Transaction rollback, durable retries, dead-letter and redrive | Failure-injection tests |
| NFR-05 | Operational visibility (extension) | Summary, readiness, audit, logs, outbox | API and manual tests |
| NFR-06 | Reproducible setup (extension) | Docker Compose, Flyway, Maven bootstrap, CI configuration | Clean build/deployment tests |

## Evidence discipline

`docs/VERIFICATION_RESULTS.md` records only commands executed in the current environment. PostgreSQL/Testcontainers tests and Selenium tests must be run in an environment with Docker/browser/network dependencies before release. Source compilation is not a substitute for integration-test evidence.
