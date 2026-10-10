# Test strategy and release gates

## Layers

1. **Dependency-free core tests** — fast regression checks for validation, idempotency, sequence behavior, retries, persistence, and concurrent replay in the demo model. Run with `verify-local.sh`/`.bat`.
2. **PostgreSQL integration tests** — primary verification for production behavior. Testcontainers runs the real PostgreSQL schema/migrations and checks database constraints, multi-instance submission, worker sequencing, journal balance/immutability, reversals, currency precision, and retry safety.
3. **API/contract checks** — exercise HTTP authentication, body size, JSON parsing, status codes, batch isolation, and safe errors against the running Compose stack. Add these to CI as the API grows.
4. **Browser automation** — Selenium WebDriver + Java + Cucumber + TestNG validates the landing page and liveness path. Browser tests do not prove financial correctness.
5. **Manual tests** — 66 scenarios in `MANUAL_TEST_SCENARIOS.md`, including failure and security cases.
6. **Load/fault testing** — required before production; measure throughput, tail latency, DB contention, backlog drain, rate limiting, restart behavior, and failure recovery under controlled load.

## Current integration tests

`production-multinode/src/test/java/com/example/txn/production/PgTransactionServiceIntegrationTest.java` includes tests for:

- Exact duplicate replay with one double-entry posting.
- Conflicting reuse of idempotency key.
- Out-of-order sequence gap and drain.
- Balanced/immutable journal and full reversal.
- Retry-safe controlled transient failure.
- Concurrent duplicate submission through two independent service instances.
- Two worker instances processing adjacent account sequences.
- Currency minor-unit precision.

## Commands

```bash
# Offline source compilation and 18-test demo regression suite
./verify-local.sh

# PostgreSQL/Testcontainers tests (Docker Engine must be running)
./verify-production.sh

# Browser suite (service and Chrome/Chromium must be available)
./verify-automation.sh
```

Windows equivalents are `verify-local.bat`, `verify-production.bat`, and the command in `README.md` for automation.

## Release gates

Do not label a build production-ready until:

- All compilation, unit, and PostgreSQL integration tests pass on a clean checkout.
- Cross-instance duplicate and adjacent-sequence tests pass repeatedly.
- Database restart and transaction interruption tests demonstrate no partial financial effect.
- The journal trigger rejects unbalanced journals and any update/delete.
- Authentication, authorization, rate limiting, secret management, and transport security have been reviewed.
- Load tests demonstrate the agreed SLOs and recovery objectives.
- Database backups and restore drills succeed.
- Remaining limitations and risk acceptance are documented by the owner.

No finite test suite proves zero defects. It can only increase confidence against explicitly tested failure modes.
