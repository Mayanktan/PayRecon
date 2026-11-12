# How to run PayRecon

This guide takes you from nothing to a full reconciliation, step by step. If you just want to see everything once, run `./run-demo.sh` (step 0) and read along.

---

## What you need

| Tool | Version | Check with | Install (macOS) |
|---|---|---|---|
| Java (JDK) | 17 | `java -version` | `brew install openjdk@17` |
| Maven | 3.9+ | `mvn -v` | `brew install maven` |
| Docker + Compose | any recent | `docker compose version` | Docker Desktop, or `brew install colima docker docker-compose` |

- **Docker memory:** give Docker at least **4 GB** of memory, because Hive alone needs about 1.5 GB. With Colima: `colima start --cpu 4 --memory 4`.
- **Free ports:** 3307 (MySQL), 27017 (MongoDB), 10000 and 10002 (Hive), 8090 (the API).
- **Disk:** about 3 GB for the Docker images and the data.
- **Operating system:** this works on macOS and Linux. On Windows, use WSL2 (the Linux layer for Windows) with Docker Desktop.

Some words used below:

- **Container:** a small isolated box that runs one program (MySQL, MongoDB, Hive) with everything it needs, separate from your computer's own software.
- **Docker Compose:** starts and stops the three containers together, using `docker-compose.yml`.
- **CLI:** command-line interface. Here it is the `./payrecon` script you type commands into.

---

## Step 0: the one-command demo (optional)

```bash
cd payrecon
./run-demo.sh                   # 200,000 payments; pauses between steps (press Enter)
PAYMENTS=50000 ./run-demo.sh    # smaller and faster
NO_PAUSE=1 ./run-demo.sh        # no pauses
```

It runs every step below in order. The rest of this guide explains each step so you can run them yourself.

---

## Step 1: passwords (the .env file)

Passwords are never written in the source code. They live in a file called `.env`, which git ignores, so it is never committed.

```bash
cp .env.example .env
# open .env and replace the two "change-me" values with your own passwords
```

(`run-demo.sh` creates `.env` with random passwords if it is missing.)

> If you change the passwords **after** MySQL has started once, MySQL keeps the old ones in its data volume. Run `docker compose down -v` (this deletes the database data) and start again.

## Step 2: start the databases

```bash
docker compose up -d        # -d = run in the background
docker compose ps           # wait until mysql and mongo show "healthy"
```

Hive needs about a minute to start. Check that everything answers:

```bash
./payrecon status
```

The first `./payrecon` command also builds the project with Maven, which takes about a minute the first time. After that it only rebuilds when you change the code.

## Step 3: create tables, indexes and Hive tables

```bash
./payrecon init                   # safe to run again; keeps data
./payrecon init --reset --yes     # wipe EVERYTHING and start fresh
```

This creates:
- 15 tables in MySQL (the ledger and the reconciliation workflow), plus the system accounts
- indexes in MongoDB
- the `payrecon` database in Hive, with 4 external tables (Hive tables that read files the app writes)

## Step 4: generate a month of business

```bash
./payrecon generate                                   # 1,000 merchants, 200,000 payments, October 2026
./payrecon generate --merchants 300 --payments 50000  # smaller
./payrecon generate --seed 7                          # different (but repeatable) data
```

This writes:
- **The ledger in MySQL:** merchants, payments, refunds and payouts, every one as a balanced journal entry.
- **Bank files** in `data/bank_files/`: HDFC (CSV), ICICI (pipe-separated) and CARDNET (JSON lines), one file per bank per day.
- **The answer key** `data/answer_key.csv`: every problem that was deliberately planted in the bank files.

The generator only runs on an empty ledger. To start over, run `./payrecon init --reset --yes` first.

## Step 5: reconcile the month

```bash
./payrecon reconcile --period 2026-10 --through 2026-10-31
```

- `--period` is the month whose payments we check.
- `--through` is the cutoff: only bank files dated on or before this day are used.

You will see six steps:
1. Extract the ledger from MySQL.
2. Run the 3 MapReduce jobs.
3. Load exceptions into MySQL.
4. Book the cash into the ledger.
5. Publish the results for Hive.
6. Store records in MongoDB.

Then it prints a summary: what matched, the exceptions, the **ledger tie-out per bank** (TIES OUT means every rupee is explained) and the time taken by each step.

Optional flags:
- `--skip-hive` skips Hive.
- `--skip-mongo` skips MongoDB.

Both are useful if one of them is not running.

## Step 6: prove the result

```bash
./payrecon verify            # the latest run
./payrecon verify --run 1
```

This compares the run with the answer key. You want: **RESULT: PASS - every planted problem was caught, and nothing extra was reported**.

## Step 7: Hive reports

```bash
./payrecon report close       # month-end close per bank
./payrecon report aging       # how old the unsettled payments are
./payrecon report breakdown   # exceptions by type and bank + top 10 merchants (RANK window function)
./payrecon report lag         # days each bank takes to settle (average, 95th percentile)
./payrecon report daily       # auto-match rate per day + running total (window function)
./payrecon report all         # all five
./payrecon report refresh     # re-create the Hive tables and re-scan partitions (if Hive was down earlier)
```

Each report takes 3–8 seconds, because Hive starts a small job for every query.

## Step 8: work the exceptions queue

```bash
./payrecon exceptions list                          # actionable ones, highest priority first
./payrecon exceptions list --all --limit 50         # including informational ones (in transit, ...)
./payrecon exceptions list --type MISSING_IN_BANK
./payrecon exceptions claim --analyst alice         # take the next item from the queue
./payrecon exceptions show 12                       # details + history + the original bank record from MongoDB
./payrecon exceptions resolve 12 --analyst alice --resolution BANK_CORRECTED --note "bank re-sent it"
./payrecon exceptions release 12 --analyst alice    # put it back in the queue
```

The resolutions are `WRITE_OFF`, `BANK_CORRECTED`, `LEDGER_CORRECTED` and `NO_ACTION`.

## Step 9: reconcile again a few days later

Payments made on the last days of October settle in early November:

```bash
./payrecon reconcile --period 2026-10 --through 2026-11-05
./payrecon verify
./payrecon runs                 # all runs side by side
```

The second run books only what changed. Bank lines that were unidentified before and are matched now are moved from the SUSPENSE account to the bank's clearing account.

## Step 10: the REST API and the stress test

Terminal 1:

```bash
./payrecon serve                # http://localhost:8090, stop with Ctrl+C
```

Terminal 2:

```bash
# create a payment (the Idempotency-Key header is required)
curl -s -X POST localhost:8090/v1/payments \
  -H 'Idempotency-Key: my-first-key' -H 'Content-Type: application/json' \
  -d '{"merchant_id":"M00001","amount_minor":49900,"method":"UPI"}'

# send the SAME request again: same payment comes back, header "Idempotent-Replayed: true", no double charge
curl -s -i -X POST localhost:8090/v1/payments \
  -H 'Idempotency-Key: my-first-key' -H 'Content-Type: application/json' \
  -d '{"merchant_id":"M00001","amount_minor":49900,"method":"UPI"}'

# refund part of it (use the payment_id you got back)
curl -s -X POST localhost:8090/v1/payments/<payment_id>/refunds \
  -H 'Idempotency-Key: refund-1' -H 'Content-Type: application/json' -d '{"amount_minor":10000}'

curl -s localhost:8090/v1/merchants/M00001/balance       # from the MySQL ledger
curl -s localhost:8090/v1/merchants/M00001/statements    # from MongoDB

# the concurrency stress test (fires many requests at the same instant)
./payrecon stress
```

`amount_minor` is in paise: 49900 means ₹499.00.

## Step 11: more commands

```bash
./payrecon audit                               # 9 rules every correct ledger must satisfy
./payrecon statement --merchant M00001         # payout statements from MongoDB
./payrecon help                                # every command
```

---

## Look inside the databases yourself

**MySQL.** The container already knows the password:

```bash
docker exec -it payrecon-mysql sh -c 'mysql -upayrecon -p"$MYSQL_PASSWORD" payrecon'
```

```sql
SHOW TABLES;
SELECT code, type, balance_minor FROM account WHERE merchant_id IS NULL;
SELECT e.entry_type, a.code, p.direction, p.amount_minor
  FROM journal_entry e JOIN posting p ON p.entry_id = e.entry_id JOIN account a ON a.account_id = p.account_id
  WHERE e.external_ref = 'pay_0000000001';
SELECT exception_type, COUNT(*) FROM recon_exception WHERE run_id = 1 GROUP BY exception_type;
```

**MongoDB:**

```bash
docker exec -it payrecon-mongo mongosh payrecon
```

```js
db.bank_raw_records.findOne({ bank: "CARDNET" })
db.bank_raw_records.find({ parse_status: "MALFORMED" })
db.payout_statements.findOne({ merchant_id: "M00001" }, { payments: { $slice: 2 } })
```

**Hive:**

```bash
docker exec -it payrecon-hive beeline -u jdbc:hive2://localhost:10000/payrecon
```

```sql
SHOW TABLES;
SHOW PARTITIONS recon_matches;
SELECT match_pass, COUNT(*) FROM recon_matches WHERE run_id = 1 GROUP BY match_pass;
```

**Files:**
- `data/bank_files/` holds the bank files.
- `data/work/run_N/` holds MapReduce inputs and outputs for each run.
- `data/warehouse/` holds what Hive reads.

## Run the unit tests

```bash
mvn test        # 27 tests: money, the 3 bank parsers, the 3 matchers, the ledger rules
```

## Stop and clean up

```bash
docker compose stop          # stop the containers, keep all data (frees about 2 GB of memory)
docker compose start         # start them again later
docker compose down          # remove the containers (the database data stays in Docker volumes)
docker compose down -v       # remove containers AND the database data
rm -rf data target           # remove generated files and the build
```

---

## Troubleshooting

| You see | Do this |
|---|---|
| `PAYRECON_MYSQL_PASSWORD is not set` | Create `.env` (step 1) |
| `MySQL error: Communications link failure` | `docker compose up -d`, wait until `docker compose ps` shows **healthy** |
| `Access denied for user 'payrecon'` | You changed `.env` after the first start: `docker compose down -v` then `docker compose up -d` |
| Hive `NOT READY` | Wait 1–2 minutes after starting; check with `docker logs payrecon-hive`. Then run `./payrecon report refresh` |
| `The ledger already has generated payments` | `./payrecon init --reset --yes`, then `generate` again |
| `port is already allocated` | Another program uses that port. Change the left-hand port in `docker-compose.yml` and set the matching value in `.env` (for example `PAYRECON_MYSQL_URL=jdbc:mysql://localhost:3308/payrecon`). For the API use `--port` |
| Java errors such as `UnsupportedClassVersionError` | Use Java 17: on macOS `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` |
| Hive container keeps restarting | Give Docker more memory (4 GB or more) |
| `The API is not reachable` during `stress` | Start `./payrecon serve` in another terminal first |
| Anything else | Add `--debug` to the command to see the full error |
