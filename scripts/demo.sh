#!/usr/bin/env bash
#
# Walks the ledger through everything it claims to do, against a running
# instance. Every step prints what it expects, so a failure is obvious without
# reading the code.
#
#   ./scripts/demo.sh [base-url]        # default http://localhost:8080
#
set -euo pipefail

BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '  \033[32mok\033[0m   %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }

# expect <http-status> <description> <curl args...>
expect() {
  local want="$1" desc="$2"; shift 2
  local body status
  body=$(curl -sS -o /tmp/obol-body -w '%{http_code}' "$@") || true
  status="$body"
  if [[ "$status" == "$want" ]]; then
    ok "$desc (HTTP $status)"
  else
    bad "$desc — wanted HTTP $want, got $status"
    sed 's/^/       /' /tmp/obol-body; echo
  fi
}

json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

RUN=$(date +%s)          # keeps account codes unique across repeated runs
acct() { echo "demo${RUN}:$1"; }

# --------------------------------------------------------------- accounts

say "1. Open the chart of accounts"

mkacct() {
  curl -sS -X POST "$BASE/v1/accounts" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$(acct "$1")\",\"name\":\"$2\",\"currency\":\"USD\",\"type\":\"$3\",\"allowNegative\":$4}" \
    > /dev/null
  ok "$(acct "$1") — $3"
}

# The bank's own cash account faces the outside world, so it is allowed to go
# negative; the customer wallets are not.
mkacct cash    "House cash"      ASSET     true
mkacct alice   "Alice wallet"    LIABILITY false
mkacct bob     "Bob wallet"      LIABILITY false
mkacct fees    "Fee revenue"     REVENUE   true

# ------------------------------------------------------------- funding

say "2. Fund Alice with 100.00 — debit cash, credit her wallet"

expect 201 "deposit posts" -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: deposit-$RUN" \
  -d "{\"currency\":\"USD\",\"externalId\":\"deposit-$RUN\",\"description\":\"initial funding\",
       \"legs\":[{\"accountCode\":\"$(acct cash)\",\"direction\":\"DEBIT\",\"amountMinor\":10000},
                 {\"accountCode\":\"$(acct alice)\",\"direction\":\"CREDIT\",\"amountMinor\":10000}]}"

BAL=$(curl -sS "$BASE/v1/accounts/$(acct alice)/balance" | json "['available']")
[[ "$BAL" == "100.00" ]] && ok "Alice has $BAL available" || bad "Alice has $BAL, wanted 100.00"

# --------------------------------------------------------- idempotency

say "3. Idempotency"

expect 201 "same key + same body replays instead of paying twice" -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: deposit-$RUN" \
  -d "{\"currency\":\"USD\",\"externalId\":\"deposit-$RUN\",\"description\":\"initial funding\",
       \"legs\":[{\"accountCode\":\"$(acct cash)\",\"direction\":\"DEBIT\",\"amountMinor\":10000},
                 {\"accountCode\":\"$(acct alice)\",\"direction\":\"CREDIT\",\"amountMinor\":10000}]}"

BAL=$(curl -sS "$BASE/v1/accounts/$(acct alice)/balance" | json "['available']")
[[ "$BAL" == "100.00" ]] && ok "still $BAL — the retry moved no money" || bad "retry double-posted: $BAL"

expect 409 "same key + different body is refused" -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: deposit-$RUN" \
  -d "{\"currency\":\"USD\",\"legs\":[{\"accountCode\":\"$(acct cash)\",\"direction\":\"DEBIT\",\"amountMinor\":99999},
                 {\"accountCode\":\"$(acct alice)\",\"direction\":\"CREDIT\",\"amountMinor\":99999}]}"

expect 400 "no idempotency key at all is refused" -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' \
  -d "{\"currency\":\"USD\",\"legs\":[{\"accountCode\":\"$(acct cash)\",\"direction\":\"DEBIT\",\"amountMinor\":1},
                 {\"accountCode\":\"$(acct alice)\",\"direction\":\"CREDIT\",\"amountMinor\":1}]}"

# ------------------------------------------------------- the rules hold

say "4. The rules the ledger will not bend"

expect 422 "unbalanced legs are rejected" -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: unbalanced-$RUN" \
  -d "{\"currency\":\"USD\",\"legs\":[{\"accountCode\":\"$(acct alice)\",\"direction\":\"DEBIT\",\"amountMinor\":500},
                 {\"accountCode\":\"$(acct bob)\",\"direction\":\"CREDIT\",\"amountMinor\":499}]}"

expect 422 "Alice cannot spend 500.00 when she holds 100.00" -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: overdraft-$RUN" \
  -d "{\"currency\":\"USD\",\"legs\":[{\"accountCode\":\"$(acct alice)\",\"direction\":\"DEBIT\",\"amountMinor\":50000},
                 {\"accountCode\":\"$(acct bob)\",\"direction\":\"CREDIT\",\"amountMinor\":50000}]}"

# --------------------------------------------------- three-leg payment

say "5. Alice pays Bob 20.00, of which 0.50 is our fee — one transfer, three legs"

expect 201 "split payment posts atomically" -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: pay-$RUN" \
  -d "{\"currency\":\"USD\",\"externalId\":\"pay-$RUN\",\"description\":\"payment with fee\",
       \"legs\":[{\"accountCode\":\"$(acct alice)\",\"direction\":\"DEBIT\",\"amountMinor\":2000},
                 {\"accountCode\":\"$(acct bob)\",\"direction\":\"CREDIT\",\"amountMinor\":1950},
                 {\"accountCode\":\"$(acct fees)\",\"direction\":\"CREDIT\",\"amountMinor\":50}]}"

for pair in "alice 80.00" "bob 19.50" "fees 0.50"; do
  set -- $pair
  GOT=$(curl -sS "$BASE/v1/accounts/$(acct "$1")/balance" | json "['available']")
  [[ "$GOT" == "$2" ]] && ok "$1 = $GOT" || bad "$1 = $GOT, wanted $2"
done

# ------------------------------------------------- authorise and capture

say "6. Two-phase: authorise 30.00, watch it reserve, then capture"

PENDING=$(curl -sS -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: auth-$RUN" \
  -d "{\"currency\":\"USD\",\"pending\":true,\"pendingTtl\":\"PT30M\",\"externalId\":\"auth-$RUN\",
       \"legs\":[{\"accountCode\":\"$(acct alice)\",\"direction\":\"DEBIT\",\"amountMinor\":3000},
                 {\"accountCode\":\"$(acct bob)\",\"direction\":\"CREDIT\",\"amountMinor\":3000}]}" \
  | json "['id']")
ok "authorised $PENDING"

read -r SETTLED AVAIL < <(curl -sS "$BASE/v1/accounts/$(acct alice)/balance" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['settled'],d['available'])")
[[ "$SETTLED" == "80.00" && "$AVAIL" == "50.00" ]] \
  && ok "settled still $SETTLED, but only $AVAIL is spendable — the hold works" \
  || bad "settled=$SETTLED available=$AVAIL, wanted 80.00 / 50.00"

POSTINGS=$(curl -sS "$BASE/v1/accounts/$(acct alice)/postings" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")
[[ "$POSTINGS" == "2" ]] && ok "no posting written for the hold (still $POSTINGS)" \
                         || bad "expected 2 postings, found $POSTINGS"

expect 200 "capture settles it" -X POST "$BASE/v1/transfers/$PENDING/capture"
expect 409 "capturing twice is refused" -X POST "$BASE/v1/transfers/$PENDING/capture"

GOT=$(curl -sS "$BASE/v1/accounts/$(acct alice)/balance" | json "['settled']")
[[ "$GOT" == "50.00" ]] && ok "Alice settled at $GOT" || bad "Alice settled at $GOT, wanted 50.00"

say "7. Two-phase: authorise, then void"

VOIDME=$(curl -sS -X POST "$BASE/v1/transfers" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: void-$RUN" \
  -d "{\"currency\":\"USD\",\"pending\":true,\"pendingTtl\":\"PT30M\",
       \"legs\":[{\"accountCode\":\"$(acct alice)\",\"direction\":\"DEBIT\",\"amountMinor\":1000},
                 {\"accountCode\":\"$(acct bob)\",\"direction\":\"CREDIT\",\"amountMinor\":1000}]}" \
  | json "['id']")

expect 200 "void releases the hold" -X POST "$BASE/v1/transfers/$VOIDME/void"
GOT=$(curl -sS "$BASE/v1/accounts/$(acct alice)/balance" | json "['available']")
[[ "$GOT" == "50.00" ]] && ok "available back to $GOT" || bad "available $GOT, wanted 50.00"

# ------------------------------------------------------------- integrity

say "8. Integrity check over the whole ledger"

REPORT=$(curl -sS "$BASE/v1/admin/verify")
HEALTHY=$(echo "$REPORT" | json "['healthy']")
SUMS=$(echo "$REPORT" | json "['sumByCurrency']")
[[ "$HEALTHY" == "True" ]] && ok "healthy — every currency sums to zero: $SUMS" \
                           || { bad "INTEGRITY FAILURE"; echo "$REPORT"; }

printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
