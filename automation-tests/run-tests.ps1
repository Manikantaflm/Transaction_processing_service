param([string]$BaseUrl = "http://localhost:8080")
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
try {
    Invoke-WebRequest -Uri "$($BaseUrl.TrimEnd('/'))/health/live" -UseBasicParsing | Out-Null
} catch {
    throw "Start the Docker Compose stack first; service not reachable at $BaseUrl"
}
& (Join-Path $Root "..\mvnw.cmd") -f (Join-Path $Root "pom.xml") clean test "-Dheadless=true" "-DbaseUrl=$($BaseUrl.TrimEnd('/'))"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
