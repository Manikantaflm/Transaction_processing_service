# Payment & Idempotent Transaction Processing Platform

**FDE L2 engineering project · Java 21 · PostgreSQL 16 · HikariCP · Flyway · Docker Compose · Selenium/Cucumber/TestNG**

This repository implements the supplied FDE assessment and extends it with multi-instance coordination, a double-entry journal, explicit operations controls, and real-PostgreSQL integration tests. It is an engineering assessment/reference implementation, **not a certified payment processor and not authorized for real-money movement**.

## Quick start (no global Maven installation)

### Prerequisites

- Docker Desktop (Windows/macOS) or Docker Engine + Docker Compose v2 (Linux).
- JDK 21+ for local Java verification and test commands. The containerized service itself runs on a Java 21 JRE image.
- Internet access the first time Docker images or Maven dependencies are downloaded.

### Windows

1. Extract this ZIP to a folder with a short path (for example `C:\work\payment-transaction-platform`).
2. Copy `.env.example` to `.env`.
3. Edit `.env`. Set three different values for `DB_PASSWORD`, `API_BEARER_TOKEN`, and `OPS_BEARER_TOKEN`. The sample values are placeholders and must be replaced.
4. Open Command Prompt in the project folder and run:

   ```bat
   run.bat
   ```

5. Wait for PostgreSQL and both application instances to become healthy. Open <http://localhost:8080/>.

### Linux/macOS

```bash
cp .env.example .env
# Edit .env and replace the sample database password and both bearer tokens.
./run.sh
```

The Compose stack starts PostgreSQL, **two independent application instances**, and Nginx as a local load balancer. Both workers share the same database. Flyway migrations run on startup and use Flyway's PostgreSQL locking so concurrent instance startup does not apply migrations simultaneously.

Stop with `Ctrl+C`. To stop and remove containers but retain the database volume, run `docker compose down`. To delete all local database data as well, run `docker compose down -v` (destructive).

## Verify the project

### Offline checks (can run without Maven or Docker)

Windows:

```bat
verify-local.bat
```

Linux/macOS:

```bash
./verify-local.sh
```

This compiles both Java source trees with `javac --release 21 -Xlint:all -Werror` and executes the dependency-free demo test suite. It does **not** test PostgreSQL behavior.

### PostgreSQL production-module integration tests

These tests use Testcontainers and a real PostgreSQL container, including constraints, migrations, concurrency, and journal triggers. Docker must be running. Maven is bootstrapped locally on first use by `mvnw`/`mvnw.cmd`; no global Maven installation is needed, but internet access is required for Maven and dependencies.

Windows:

```bat
verify-production.bat
```

Linux/macOS:

```bash
./verify-production.sh
```

Reports are written under `production-multinode/target/surefire-reports/`.

### Selenium browser automation

The production service must be running, and Chrome/Chromium must be installed. The first run downloads Maven dependencies and a compatible browser driver. Set `BASE_URL` if the service is not at `http://localhost:8080`.

Windows:

```bat
mvnw.cmd -f automation-tests/pom.xml clean test -DbaseUrl=http://localhost:8080 -Dheadless=true
```

Linux/macOS:

```bash
./verify-automation.sh
```

Reports: `automation-tests/target/cucumber-report.html` and `automation-tests/target/cucumber-report.json`.

### Offline core demo

`run-demo.bat` / `./run-demo.sh` starts a separate, single-process, file-backed teaching/demo implementation. It is useful for exploring the business flow without Docker, but it is **not** the multi-instance implementation and must not be used as evidence of cross-node safety.

## API quick reference

The local Nginx endpoint is `http://localhost:8080`. All `/v1` routes require a bearer token. Transaction submission uses `API_BEARER_TOKEN`; account management, account/ledger reads, summary, readiness details, and redrive use the separate `OPS_BEARER_TOKEN`.

Create an account (operations token):

```bash
curl -i -X POST http://localhost:8080/v1/accounts \
  -H 'Authorization: Bearer replace-with-ops-token' \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-201","currency":"USD"}'
```

Submit a transaction (API token):

```bash
curl -i -X POST http://localhost:8080/v1/transactions \
  -H 'Authorization: Bearer replace-with-api-token' \
  -H 'Content-Type: application/json' \
  -d '{"transactionId":"TXN-2001","requestId":"REQ-1","idempotencyKey":"KEY-1","accountId":"ACC-201","transactionType":"CREDIT","amount":100.00,"currency":"USD","sequenceNumber":1,"eventTime":"2026-10-10T08:00:00Z"}'
```

The endpoint returns `202 Accepted` after durable ingestion. The background worker processes the record asynchronously. Query account balance with the operations token:

```bash
curl http://localhost:8080/v1/accounts/ACC-201 \
  -H 'Authorization: Bearer replace-with-ops-token'
```

See [`docs/API.md`](docs/API.md) for every endpoint and request contract. For a running stack, load the local credentials and run the end-to-end smoke check (Linux/macOS):

```bash
set -a; . ./.env; set +a
./smoke-test.sh
```

This verifies asynchronous posting, duplicate replay, account balance, and the two-line journal.

## Architecture at a glance

```text
Client / Batch API
        |
        v
Nginx reverse proxy + request rate limit
        |
        +--------------------+
        |                    |
        v                    v
 Application node 1     Application node 2
        |                    |
        +----------+---------+
                   |
                   v
        Shared PostgreSQL database
        - unique transaction/idempotency keys
        - durable transaction state and retry times
        - account row locks + per-account sequence cursor
        - immutable double-entry journal
        - transactional outbox + audit records
```

The database is the coordination boundary. There are no process-local locks used as the correctness mechanism. Workers claim rows with `FOR UPDATE SKIP LOCKED`, then lock the account row before sequence and balance decisions. The transaction row, journal lines, account balance/cursor, status, audit event, and outbox event commit or roll back together.

### Deliberate framework choice

The HTTP adapter uses Java 21's built-in `HttpServer` rather than Spring MVC. The FDE requirement is a small API and background processor with no complex UI; keeping the transport adapter small makes the business/data concurrency logic explicit and permits strict offline compilation of the production Java source. HikariCP and Flyway are used for production database connectivity and schema migrations. If the API surface grows substantially, Spring Boot is a sensible next step, but it is not mixed into this codebase as a second runtime framework.

## Important design semantics

- **Idempotency:** transaction ID and idempotency key are separate unique identities. The canonical payload hash excludes delivery-specific `requestId` and idempotency key. Reusing an identity with a different transaction/payload returns `409 Conflict`; replaying the same transaction returns the original stored outcome and does not post again.
- **Status:** `DUPLICATE` is a submission outcome recorded in the audit trail, not a mutation of the original transaction's stored status.
- **Ordering:** sequences start at 1 and are monotonic per account. Future sequences stay `PENDING`. Once an expected sequence is evaluated, a permanent business rejection consumes the sequence cursor so it does not block the account forever; the rejection has no ledger effect. Missing sequence numbers are never skipped automatically.
- **Money:** values use `BigDecimal`/PostgreSQL `NUMERIC(19,4)` and must respect the currency's minor-unit precision from the JDK ISO currency data. Floating-point arithmetic is not used for balances.
- **Ledger:** successful transactions create two balanced lines (customer and currency-specific clearing account). Posted ledger lines are append-only. A reversal must reference a previously processed CREDIT/DEBIT for the same account, currency, and full amount. The reference is checked when its sequence becomes eligible, allowing an out-of-order reversal request to wait for its original event. This assessment version supports one full reversal per original transaction; partial reversals are out of scope.
- **Retries:** database/processing transient failures are retried with bounded exponential backoff and jitter. Exhausted/unexpected infrastructure failures enter `DEAD_LETTER`; an operations credential is required to redrive a dead-lettered record. A committed posting is never redone by a replay.
- **Fault injection:** `transientFailuresToSimulate` is accepted only when `ENABLE_TEST_FAULT_INJECTION=true` and `APP_ENV` is not `production`. It is disabled by default. Never enable it in a real production environment.
- **Outbox:** an event is written atomically with a successful posting. This repository does not publish to an external broker; add a separately monitored outbox dispatcher and idempotent consumer before enabling external side effects.

## Project structure

```text
payment-transaction-platform/
├── production-multinode/             # Primary PostgreSQL-backed multi-instance service
│   ├── src/main/java/                 # HTTP API, JDBC service, connection pool adapter
│   ├── src/main/resources/db/migration # Flyway schema and database invariants
│   ├── src/test/java/                 # JUnit + Testcontainers PostgreSQL integration tests
│   ├── Dockerfile
│   └── pom.xml
├── automation-tests/                  # Separate Selenium + Cucumber + TestNG project
├── src/                               # Dependency-free, single-process demo/core test harness
├── deploy/nginx/nginx.conf            # Local load balancer and rate limiting
├── docs/                              # Architecture, API, traceability, tests, manual scenarios
│   └── reference/Requirement.docx     # Supplied assessment source
├── samples/                           # Example inputs
├── docker-compose.yml                 # PostgreSQL + two app nodes + Nginx
├── run.sh / run.bat                   # Production-oriented local stack
├── verify-local.sh / verify-local.bat # Offline compile and demo tests
├── verify-production.sh / .bat        # PostgreSQL/Testcontainers integration tests
├── verify-automation.sh               # Browser automation runner
├── smoke-test.sh                      # API + async posting end-to-end smoke test
└── README.md
```

## Security and production deployment notes

- The Compose profile is a local development/test environment. It disables PostgreSQL TLS on the private Docker network and uses placeholder credentials from `.env.example` only to help you bootstrap.
- Before any real deployment: use a managed PostgreSQL service with TLS, managed secret storage, network policies, an HTTPS ingress, OAuth2/OIDC or workload identity, role-scoped authorization, database least privilege, backup/restore drills, migrations in a controlled deployment job, and an external audit retention policy.
- The shared bearer tokens are a simple assessment authentication mechanism, not a full enterprise identity solution. Rotate them and replace them with an identity provider before production exposure.
- A ledger system needs reconciliation, formal accounting review, currency/rounding sign-off, monitoring/alerting, load tests, threat modeling, penetration testing, and disaster recovery exercises before being approved for real funds.
- No system can honestly guarantee zero defects or zero chance of failure. The goal is to enforce invariants, make failure states visible, and prove recovery through reproducible tests.

## Requirement traceability and test evidence

- [`docs/REQUIREMENT_TRACEABILITY.md`](docs/REQUIREMENT_TRACEABILITY.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/TEST_STRATEGY.md`](docs/TEST_STRATEGY.md)
- [`docs/MANUAL_TEST_SCENARIOS.md`](docs/MANUAL_TEST_SCENARIOS.md)
- [`docs/VERIFICATION_RESULTS.md`](docs/VERIFICATION_RESULTS.md)
- [`docs/AI_ASSISTED_DEVELOPMENT.md`](docs/AI_ASSISTED_DEVELOPMENT.md)

## License and intended use

Assessment/reference code only. Review all business, security, legal, accounting, and operational assumptions before reuse. Do not use this repository to process real customer funds without a separate production readiness review.

## Operations UI (React + Framer Motion)

A separate responsive operations console is in `ui/`. It uses the existing API rather than a mock backend.

```powershell
cd ui
npm install
npm run dev
```

Open the Vite URL (normally `http://localhost:5173`), then enter the local API base URL and the two bearer tokens from `.env`. The API allows this exact local origin by default; set `UI_ALLOWED_ORIGIN` on the app services to the one trusted deployed UI origin when deploying elsewhere. Never use wildcard CORS in production. The UI includes account snapshot, summary counters, transaction submission, ledger inspection, and “copy as new transaction”.

## API regression suite

With the services running, execute `.\api-tests\run-api-tests.ps1` from PowerShell at the project root. It records each check separately to `api-tests/last-run-results.csv`. Details and expected outcomes: `docs/API_REGRESSION_TESTS.md`. Ledger integrity SQL and schema protections: `docs/LEDGER_INTEGRITY.md`.

**Important design rule:** equal amounts are not duplicates. Each new business transaction needs a new transaction ID, idempotency key, and unique per-account sequence. Replaying the same request identity/payload must not post a second balance or ledger effect. A standard posted transaction has two ledger lines (customer and clearing); these two lines are the expected double-entry journal, not duplicates.
