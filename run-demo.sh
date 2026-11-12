#!/usr/bin/env bash
# One-command PayRecon demo. It starts the databases, builds the project, generates a month of payments,
# reconciles it, proves the result against the answer key, shows Hive reports, works the exceptions queue,
# reconciles again a week later, stress-tests the REST API and audits the ledger.
#
#   ./run-demo.sh                      default size: 1,000 merchants, 200,000 payments
#   PAYMENTS=50000 ./run-demo.sh       smaller and faster
#   NO_PAUSE=1 ./run-demo.sh           do not stop between steps
set -euo pipefail
cd "$(dirname "$0")"

PAYMENTS="${PAYMENTS:-200000}"
MERCHANTS="${MERCHANTS:-1000}"
API_PORT="${PAYRECON_API_PORT:-8090}"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
pause() {
  if [ -t 0 ] && [ "${NO_PAUSE:-0}" != "1" ]; then
    read -r -p "    (press Enter to continue) " _
  fi
}

if [ ! -f .env ]; then
  step "Creating .env with random passwords (this file is git-ignored)"
  printf 'MYSQL_ROOT_PASSWORD=%s\nPAYRECON_MYSQL_USER=payrecon\nPAYRECON_MYSQL_PASSWORD=%s\n' \
    "$(openssl rand -hex 16)" "$(openssl rand -hex 16)" > .env
  chmod 600 .env
fi

step "1. Starting MySQL, MongoDB and Hive in Docker"
docker compose up -d
printf '    waiting until MySQL and MongoDB report healthy '
for _ in $(seq 1 90); do
  healthy=$(docker inspect -f '{{.State.Health.Status}}' payrecon-mysql payrecon-mongo 2>/dev/null | grep -c '^healthy$' || true)
  [ "$healthy" = "2" ] && break
  printf '.'
  sleep 2
done
echo

step "2. Building, then creating MySQL tables, MongoDB indexes and Hive external tables (waits for Hive)"
./payrecon init --reset --yes

step "3. Generating October 2026: ${MERCHANTS} merchants, ${PAYMENTS} payments, bank files with planted problems"
./payrecon generate --merchants "$MERCHANTS" --payments "$PAYMENTS"
pause

step "4. Month-end reconciliation with the bank files up to 31 October"
./payrecon reconcile --period 2026-10 --through 2026-10-31
pause

step "5. Proof: compare the results with the answer key"
./payrecon verify
pause

step "6. Hive: month-end close report"
./payrecon report close
pause

step "7. Exceptions queue: list, claim one, inspect it (with the original bank record from MongoDB), resolve it"
./payrecon exceptions list --limit 8
ID=$(./payrecon exceptions claim --analyst alice | grep -o '#[0-9]*' | head -1 | tr -d '#')
if [ -n "$ID" ]; then
  ./payrecon exceptions show "$ID"
  ./payrecon exceptions resolve "$ID" --analyst alice --resolution BANK_CORRECTED --note "demo: bank confirmed and corrected it"
fi
pause

step "8. A few days later: reconcile again including November's first bank files"
./payrecon reconcile --period 2026-10 --through 2026-11-05
./payrecon verify
pause

step "9. REST API + concurrency stress test (idempotency, refund race, parallel payments, queue claims)"
./payrecon serve --port "$API_PORT" > data/api.log 2>&1 &
API_PID=$!
trap 'kill "$API_PID" 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -s -m 2 "localhost:$API_PORT/v1/health" > /dev/null && break
  sleep 1
done
./payrecon stress --url "http://localhost:$API_PORT"

step "10. Ledger audit"
./payrecon audit

step "Done. More commands: ./payrecon help    Free the memory: docker compose stop"
