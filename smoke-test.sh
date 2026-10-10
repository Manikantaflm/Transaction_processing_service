#!/usr/bin/env sh
# End-to-end smoke test against a running PostgreSQL-backed Compose stack.
set -eu
: "${API_BEARER_TOKEN:?Export API_BEARER_TOKEN from your local .env}"
: "${OPS_BEARER_TOKEN:?Export OPS_BEARER_TOKEN from your local .env}"
BASE_URL=${BASE_URL:-http://localhost:8080}
SUFFIX="$(date +%s)-$$"
ACCOUNT="ACC-SMOKE-$SUFFIX"
TXN="TXN-SMOKE-$SUFFIX"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
request() {
  curl --silent --show-error --output "$TMP_DIR/body.json" --write-out '%{http_code}' "$@"
}
status=$(request -X POST "$BASE_URL/v1/accounts" -H "Authorization: Bearer $OPS_BEARER_TOKEN" -H 'Content-Type: application/json' -d "{\"accountId\":\"$ACCOUNT\",\"currency\":\"USD\"}")
[ "$status" = 201 ] || { cat "$TMP_DIR/body.json"; echo "Account creation failed: HTTP $status" >&2; exit 1; }
body="{\"transactionId\":\"$TXN\",\"requestId\":\"REQ-$TXN\",\"idempotencyKey\":\"KEY-$TXN\",\"accountId\":\"$ACCOUNT\",\"transactionType\":\"CREDIT\",\"amount\":10.00,\"currency\":\"USD\",\"sequenceNumber\":1,\"eventTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
status=$(request -X POST "$BASE_URL/v1/transactions" -H "Authorization: Bearer $API_BEARER_TOKEN" -H 'Content-Type: application/json' -d "$body")
[ "$status" = 202 ] || { cat "$TMP_DIR/body.json"; echo "Transaction submission failed: HTTP $status" >&2; exit 1; }
# A retry with a different delivery key/request ID but the same business payload must not post twice.
duplicate_body=$(printf '%s' "$body" | sed "s/REQ-$TXN/REQ-REPLAY-$TXN/;s/KEY-$TXN/KEY-REPLAY-$TXN/")
status=$(request -X POST "$BASE_URL/v1/transactions" -H "Authorization: Bearer $API_BEARER_TOKEN" -H 'Content-Type: application/json' -d "$duplicate_body")
[ "$status" = 202 ] || { cat "$TMP_DIR/body.json"; echo "Replay failed: HTTP $status" >&2; exit 1; }
python - "$TMP_DIR/body.json" <<'PY'
import json, sys
body=json.load(open(sys.argv[1], encoding='utf-8'))
assert body.get('duplicate') is True, body
PY
# Poll until the background worker commits the balance.
found=false
for _ in $(seq 1 40); do
  status=$(request "$BASE_URL/v1/accounts/$ACCOUNT" -H "Authorization: Bearer $OPS_BEARER_TOKEN")
  if [ "$status" = 200 ] && python - "$TMP_DIR/body.json" "$ACCOUNT" <<'PY'
import json, sys
x=json.load(open(sys.argv[1], encoding='utf-8'))
raise SystemExit(0 if x.get('balance') == '10.0000' else 1)
PY
  then found=true; break; fi
  sleep 0.25
done
[ "$found" = true ] || { echo "Balance did not converge to 10.0000" >&2; exit 1; }
status=$(request "$BASE_URL/v1/transactions/$TXN/ledger" -H "Authorization: Bearer $OPS_BEARER_TOKEN")
[ "$status" = 200 ] || { cat "$TMP_DIR/body.json"; echo "Ledger query failed: HTTP $status" >&2; exit 1; }
python - "$TMP_DIR/body.json" <<'PY'
import json, sys
x=json.load(open(sys.argv[1], encoding='utf-8'))
assert len(x.get('entries', [])) == 2, x
PY
echo "PASS: account creation, async posting, duplicate replay, balance, and double-entry journal smoke checks passed."
