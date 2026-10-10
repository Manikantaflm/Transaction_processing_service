# Selenium + Java + Cucumber + TestNG automation

This suite is intentionally separate from the backend. It validates the operations landing page and the liveness endpoint. Business/financial correctness is tested by the PostgreSQL integration suite, not by brittle browser-only assertions.

## Prerequisites

- JDK 21+
- Docker Compose stack running at `http://localhost:8080` (or provide another base URL)
- Chrome/Chromium installed
- Internet access on first run for Maven dependencies and a compatible ChromeDriver

## Run without preinstalling Maven

macOS/Linux:

```bash
./run-tests.sh
```

Windows PowerShell:

```powershell
.\run-tests.ps1
```

The scripts use the repository-level `mvnw`/`mvnw.cmd` bootstrap to download a pinned Maven distribution to the user profile on first use. Reports are written to `target/cucumber-report.html` and `target/cucumber-report.json`.

The browser suite was not executed in the offline authoring environment because Maven, Chrome/Chromium, and external dependency downloads were unavailable. Run locally or in CI before release.
