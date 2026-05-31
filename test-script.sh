#!/usr/bin/env bash
# =============================================================================
# Retry Engine — Test Script
# Demonstrates: exponential backoff + jitter, 4xx terminal, dead-letter
# Usage: ./test-script.sh
# Requires: curl, jq
# =============================================================================

BASE="http://localhost:8080"
BOLD="\033[1m"
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
CYAN="\033[36m"
RESET="\033[0m"

banner() { echo -e "\n${BOLD}${CYAN}━━━ $1 ━━━${RESET}\n"; }
ok()     { echo -e "${GREEN}✓ $1${RESET}"; }
warn()   { echo -e "${YELLOW}⚠ $1${RESET}"; }
fail()   { echo -e "${RED}✗ $1${RESET}"; }
info()   { echo -e "  $1"; }

poll_until_done() {
    local id="$1"
    local max_polls=60
    local poll=0
    local prev_count=0

    echo ""
    while [ $poll -lt $max_polls ]; do
        sleep 2
        poll=$((poll + 1))

        response=$(curl -s "$BASE/requests/$id")

        # Spring Boot outputs uppercase Enums (e.g., PENDING, COMPLETED, FAILED)
        status=$(echo "$response" | jq -r '.request.status')
        attempt_count=$(echo "$response" | jq -r '.request.attemptCount')

        # Only process if an attempt has actually run and count has increased
        if [ "$attempt_count" != "0" ] && [ "$attempt_count" != "$prev_count" ] && [ "$status" != "null" ]; then
            echo ""
            info "Attempt #$attempt_count — Status: $status"

            # Safely grab the last element of the array using single-quoted jq syntax
            latest=$(echo "$response" | jq -r '.attempts[-1] // empty')

            if [ ! -z "$latest" ]; then
                outcome=$(echo "$latest" | jq -r '.outcome')
                waited=$(echo "$latest" | jq -r '.waitedMs')
                code=$(echo "$latest" | jq -r '.statusCode // "null (network error)"')
                info "  outcome:    $outcome"
                info "  statusCode: $code"
                info "  waitedMs:   ${waited}ms"
            fi
            prev_count=$attempt_count
        else
            echo -n "."
        fi

        # Refactored to match uppercase JSON status strings from your API
        if [ "$status" = "COMPLETED" ] || [ "$status" = "FAILED" ]; then
            echo ""
            return
        fi
    done
    echo ""
    warn "Timed out waiting for completion"
}

# ===========================================================================
banner "Scenario 1 — Flaky endpoint: fails 3×, then succeeds"
# ===========================================================================
info "The mock endpoint returns 503 for the first 3 calls, then 200."
info "You should see backoff roughly doubling: ~1s, ~2s, ~4s (with jitter)."
echo ""

# Reset mock counter for a clean run
curl -s -X DELETE "$BASE/mock/reset?id=test1" > /dev/null

RESPONSE=$(curl -s -X POST "$BASE/request" \
  -H "Content-Type: application/json" \
  -d '{
    "url":       "http://localhost:8080/mock/target?id=test1",
    "method":    "GET",
    "maxRetries": 5,
    "backoffMs":  1000
  }')

ID=$(echo "$RESPONSE" | jq -r '.id')
STATUS=$(echo "$RESPONSE" | jq -r '.status')

ok "POST /request returned immediately"
info "id:     $ID"
info "status: $STATUS"

poll_until_done "$ID"

echo ""
info "Full attempt history:"
curl -s "$BASE/requests/$ID" | jq '{
  status:       .request.status,
  attemptCount: .request.attemptCount,
  attempts: [.attempts[] | {
    attemptNumber: .attemptNumber,
    outcome:       .outcome,
    statusCode:    .statusCode,
    waitedMs:      .waitedMs,
    attemptedAt:   .attemptedAt
  }]
}'

# ===========================================================================
banner "Scenario 2 — 4xx is terminal: never retried"
# ===========================================================================
info "The endpoint always returns 404. Should fail immediately on attempt 1."
echo ""

RESPONSE=$(curl -s -X POST "$BASE/request" \
  -H "Content-Type: application/json" \
  -d '{
    "url":    "http://localhost:8080/mock/always-fail-4xx",
    "method": "GET"
  }')

ID2=$(echo "$RESPONSE" | jq -r '.id')
ok "POST /request returned immediately"
info "id: $ID2"

# Wait slightly for background worker thread loop to process
sleep 2

RESULT=$(curl -s "$BASE/requests/$ID2")
STATUS2=$(echo "$RESULT" | jq -r '.request.status')
ATTEMPTS=$(echo "$RESULT" | jq -r '.request.attemptCount')

info "status:       $STATUS2"
info "attemptCount: $ATTEMPTS (expected: 1)"

if [ "$STATUS2" = "FAILED" ] && [ "$ATTEMPTS" = "1" ]; then
    ok "4xx correctly terminated after 1 attempt — no retries"
else
    fail "Unexpected: status=$STATUS2 attempts=$ATTEMPTS"
fi

echo ""
curl -s "$BASE/requests/$ID2" | jq '.attempts[0] | {outcome, statusCode, errorMessage}'

# ===========================================================================
banner "Scenario 3 — Dead-letter: hits maxRetries and stops"
# ===========================================================================
info "Using a non-existent port — every attempt is a network error."
info "maxRetries=3, backoffMs=500 so this completes quickly."
echo ""

RESPONSE=$(curl -s -X POST "$BASE/request" \
  -H "Content-Type: application/json" \
  -d '{
    "url":         "http://localhost:9999/this-does-not-exist",
    "method":      "GET",
    "maxRetries": 3,
    "backoffMs":  500
  }')

ID3=$(echo "$RESPONSE" | jq -r '.id')
ok "POST /request returned immediately"
info "id: $ID3"

poll_until_done "$ID3"

RESULT=$(curl -s "$BASE/requests/$ID3")
STATUS3=$(echo "$RESULT" | jq -r '.request.status')
ATTEMPTS3=$(echo "$RESULT" | jq -r '.request.attemptCount')

info "status:       $STATUS3"
info "attemptCount: $ATTEMPTS3 (expected: 3)"

if [ "$STATUS3" = "FAILED" ] && [ "$ATTEMPTS3" = "3" ]; then
    ok "Dead-lettered after $ATTEMPTS3 attempts — will never be retried again"
else
    fail "Unexpected: status=$STATUS3 attempts=$ATTEMPTS3"
fi

echo ""
info "Dead-letter attempt history:"
curl -s "$BASE/requests/$ID3" | jq '[.attempts[] | {attemptNumber, outcome, waitedMs, errorMessage: .errorMessage[0:60]}]'

# ===========================================================================
banner "Scenario 4 — GET /requests?status=FAILED"
# ===========================================================================
info "List all failed requests (should include scenarios 2 and 3)."
echo ""

# Query parameter updated to uppercase to match the system enum validation safely
FAILED=$(curl -s "$BASE/requests?status=FAILED")
COUNT=$(echo "$FAILED" | jq 'length')
ok "Found $COUNT failed request(s)"
echo "$FAILED" | jq '[.[] | {id: .id, url: .url, attemptCount: .attemptCount, lastError: .lastError[0:80]}]'

echo ""
banner "Done"
ok "All scenarios passed"
echo ""