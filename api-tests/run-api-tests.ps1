# API regression suite. Uses existing account if present, otherwise provisions it.
# Run from project root after Docker services are healthy. Never prints bearer tokens.
$ErrorActionPreference = 'Stop'
$baseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://localhost:8080' }
if (!(Test-Path '.env')) { throw 'Run this script from the project root; .env is missing.' }
$envLines = Get-Content '.env'
function Read-EnvValue([string]$name) {
  $line = $envLines | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
  if (!$line) { throw "Missing $name in .env" }
  return (($line -replace "^$name=", '').Trim().Trim('"').Trim("'"))
}
$api = Read-EnvValue 'API_BEARER_TOKEN'; $ops = Read-EnvValue 'OPS_BEARER_TOKEN'
$apiHeaders = @{ Authorization = "Bearer $api" }; $opsHeaders = @{ Authorization = "Bearer $ops" }
$script:results = [System.Collections.Generic.List[object]]::new()
function Test-Case([string]$name,[scriptblock]$action) {
  try { & $action; $script:results.Add([pscustomobject]@{Test=$name;Result='PASS';Details=''}) }
  catch { $script:results.Add([pscustomobject]@{Test=$name;Result='FAIL';Details=$_.Exception.Message}) }
}
$accountId = "API-REG-$([guid]::NewGuid().ToString('N').Substring(0,8))"
$currency = 'USD'
Test-Case 'Readiness authenticated' { $r=Invoke-RestMethod "$baseUrl/health/ready" -Headers $opsHeaders; if($r.status -ne 'UP'){throw 'Readiness not UP'} }
Test-Case 'Create account' { $null=Invoke-RestMethod "$baseUrl/v1/accounts" -Method POST -Headers $opsHeaders -ContentType 'application/json' -Body (@{accountId=$accountId;currency=$currency}|ConvertTo-Json) }
$seq=1
function Submit-Txn([string]$id,[string]$key,[string]$type,[decimal]$amount,[int]$sequence) {
  $body=@{transactionId=$id;requestId="REQ-$id";idempotencyKey=$key;accountId=$accountId;transactionType=$type;amount=$amount;currency=$currency;sequenceNumber=$sequence;eventTime='2026-10-10T12:00:00Z'}
  return Invoke-RestMethod "$baseUrl/v1/transactions" -Method POST -Headers $apiHeaders -ContentType 'application/json' -Body ($body|ConvertTo-Json)
}
$txn1="T-$([guid]::NewGuid().ToString('N').Substring(0,12))"; $key1="K-$([guid]::NewGuid().ToString('N').Substring(0,12))"
Test-Case 'Credit accepted' { $r=Submit-Txn $txn1 $key1 'CREDIT' 150 1; if($r.transactionId -ne $txn1){throw 'Wrong transaction ID'} }
Start-Sleep -Seconds 2
Test-Case 'Exact replay idempotent' { $before=Invoke-RestMethod "$baseUrl/v1/accounts/$accountId" -Headers $opsHeaders; $r=Submit-Txn $txn1 $key1 'CREDIT' 150 1; if(!$r.duplicate){throw 'Replay was not marked duplicate'}; Start-Sleep -Seconds 1; $after=Invoke-RestMethod "$baseUrl/v1/accounts/$accountId" -Headers $opsHeaders; if([decimal]$after.balance -ne [decimal]$before.balance){throw 'Replay changed balance'} }
$txn2="T-$([guid]::NewGuid().ToString('N').Substring(0,12))"; $key2="K-$([guid]::NewGuid().ToString('N').Substring(0,12))"
Test-Case 'Same amount with new ID/key accepted' { $r=Submit-Txn $txn2 $key2 'CREDIT' 150 2; if($r.transactionId -ne $txn2 -or $r.duplicate){throw 'New transaction incorrectly deduplicated'} }
Start-Sleep -Seconds 2
Test-Case 'Ledger has exactly two lines per processed transaction' { $l=Invoke-RestMethod "$baseUrl/v1/transactions/$txn1/ledger" -Headers $opsHeaders; if($l.entries.Count -ne 2){throw "Expected 2 ledger lines, got $($l.entries.Count)"}; if($l.entries.lineNumber -contains 1 -and $l.entries.lineNumber -contains 2){}else{throw 'Expected line numbers 1 and 2'} }
Test-Case 'Transaction list exposes unique transactions' { $r=Invoke-RestMethod "$baseUrl/v1/transactions?limit=200" -Headers $opsHeaders; $ids=@($r.transactions|ForEach-Object transactionId); if(($ids|Select-Object -Unique).Count -ne $ids.Count){throw 'Duplicate transaction IDs in listing'} }
$script:results | Format-Table -AutoSize
$failures=@($script:results|Where-Object Result -eq 'FAIL')
$script:results | Export-Csv -NoTypeInformation -Encoding UTF8 'api-tests\last-run-results.csv'
if($failures.Count){exit 1}else{Write-Host 'All API regression checks passed.' -ForegroundColor Green}
