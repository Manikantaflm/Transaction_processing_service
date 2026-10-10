# Verification results

**Recorded date:** 2026-10-10 (UTC date)

This report distinguishes checks actually executed in the current build environment from checks that require Docker, Maven dependencies, PostgreSQL, or a browser.

## Executed successfully

| Check | Command/method | Result |
|---|---|---|
| Java runtime | `java -version` | OpenJDK 21.0.12 |
| Dependency-free demo compile | `javac --release 21 -Xlint:all -Werror` via `verify-local.sh` | PASS |
| Demo regression suite | `java -ea ... com.example.txn.SelfTest` | 18 tests passed, 0 failures |
| PostgreSQL service source compilation | `javac --release 21 -Xlint:all -Werror` via `verify-local.sh` | PASS |
| Compose YAML parse | Python PyYAML `safe_load` | PASS; YAML syntax parsed |
| Maven POM XML parse | Python XML parser | PASS; project descriptor XML is well-formed |
| Sample JSON parse | Python JSON parser | PASS |
| ZIP archive integrity | `unzip -t` after packaging | To be run after final archive creation |

The 18 executed tests belong to the single-process, file-backed core/demo implementation. They are useful regression tests, but they are not evidence that PostgreSQL multi-instance behavior passed integration testing.

## Not executed in this environment

- `mvn clean verify` for the production module: Maven is not installed and DNS/network access to dependency repositories is unavailable.
- PostgreSQL/Testcontainers integration tests: Docker is not installed in this environment.
- Selenium/Cucumber/TestNG browser tests: Maven, Chrome/Chromium driver setup, and external dependency downloads are unavailable.
- Full Docker image build and two-instance Compose startup.
- Load, soak, failover, backup/restore, and security penetration tests.

The repository includes commands and test code for these checks, but they must be executed on a machine with Docker and network access before this project can be accepted as production-ready. No unexecuted test is marked as passed.

## Remaining release gates

1. Run `verify-production.sh` with Docker running; fix every compile, migration, and Testcontainers failure.
2. Run Selenium suite against the Compose stack and retain both HTML and JSON reports.
3. Validate PostgreSQL migration syntax against the exact target image/version.
4. Run cross-instance concurrency tests repeatedly under contention.
5. Test restart/kill scenarios and confirm no partial ledger/balance update.
6. Add a dedicated API contract suite for HTTP authentication, batch isolation, and safe error behavior.
7. Perform load testing and a production security/accounting review.
