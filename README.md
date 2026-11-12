# PayRecon: payment ledger and bank reconciliation

PayRecon simulates the systems a payment company such as Stripe or Razorpay uses to make sure every rupee that should have moved actually did. It has two halves:

- **A double-entry ledger** in MySQL, behind a REST API with idempotency keys.
- **A reconciliation engine.** Hadoop MapReduce matches millions of ledger entries against the settlement files three banks send, in three different formats. Hive produces the month-end close and analytics, and MongoDB stores the raw bank records and merchant payout statements.

```
                    REST API (JDK HttpServer)                generator: 1 month of payments + 3 banks' files
        POST /v1/payments  (Idempotency-Key)                 (CSV, pipe-separated, JSON lines) + answer key
                       │                                                   │
                       ▼                                                   ▼
   ┌──────────────── MySQL: double-entry ledger ───────────┐      data/bank_files/<BANK>/...
   │ journal_entry + posting (debits = credits), accounts, │               │
   │ payments, refunds, payouts, idempotency keys          │               │
   └──────────┬────────────────────────────▲───────────────┘               │
              │ extract (streaming)        │ cash application,             │
              ▼                            │ exceptions queue              ▼
        ┌─────────────────── Hadoop MapReduce (local mode, 3 jobs) ─────────────────┐
        │ job 1: exact reference (reduce-side join)                                  │
        │ job 2: amount + date window (secondary sort)   ─ run in parallel ─         │
        │ job 3: payout batches (combiner)                                            │
        └───────┬───────────────────────────────┬────────────────────────────────────┘
                ▼                               ▼
   Hive (external, partitioned tables)    MongoDB
   month-end close, aging, lag,           raw bank records as received,
   breakdown, daily match rate            merchant payout statements (read model)
```

## Why each technology is here

| Tool | Its job | Why this tool fits |
|---|---|---|
| **MySQL** | Double-entry ledger, idempotency keys, the analysts' exception queue | Money needs ACID transactions, row locks, constraints and an audit trail |
| **Hadoop MapReduce** | The 3-pass matching engine | Matching needs custom logic, not just a SQL join: reduce-side joins, secondary sort, sliding windows and a combiner, all over every ledger entry and bank line |
| **Hive** | Month-end close and analytics | SQL over the full history of result files, kept away from the transactional database; uses partitions and window functions |
| **MongoDB** | Raw bank records, merchant payout statements | Each bank sends a different format, and documents keep each one as-is. Statements are one document each, so a merchant's statement is a single read |

## Results (October 2026: 1,000 merchants, 200,000 payments)

| | |
|---|---|
| Ledger written (200k payments, 3.5k refunds, 9.3k payouts) | 11.8 s |
| Reconciliation, all 6 steps (cutoff 31 Oct) | **21.9 s** total, of which MapReduce took 7.7 s |
| Auto-matched (of payments due by the cutoff) | **99.83%** (99.92% after the next week's files arrive) |
| Planted problems caught (`verify`) | **all of them, 0 false alarms**; anything ambiguous is sent to a person, never guessed |
| Ledger tie-out per bank | every bank **TIES OUT** to the paisa |
| 50 simultaneous requests, same idempotency key | exactly **1** payment created, 49 replays |
| 20 simultaneous ₹100 refunds on a ₹1,000 payment | exactly **10** succeed, never over-refunded |

## Quick start

```bash
./run-demo.sh                 # starts Docker services, builds, runs the whole story (about 5 minutes)
PAYMENTS=50000 ./run-demo.sh  # smaller and faster
```

Requirements: Java 17, Maven, and Docker with about 4 GB of memory. Full instructions are in **[RUN.md](RUN.md)**.

## Documentation

- **[RUN.md](RUN.md):** how to set up and run everything, step by step, plus troubleshooting.
- **Project Explained**: the whole project in simple English, with every technical term explained when it first appears.
  - PDF with 14 diagrams: [docs/PayRecon-Project-Explained.pdf](docs/PayRecon-Project-Explained.pdf)
  - Markdown: [docs/PROJECT_EXPLAINED.md](docs/PROJECT_EXPLAINED.md)
- **Build Plan**: how the project was planned, built and checked, the key decisions, and the bugs found on the way.
  - PDF with 6 diagrams: [docs/PayRecon-Build-Plan.pdf](docs/PayRecon-Build-Plan.pdf)
  - Markdown: [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md)
- **[docs/INTERVIEW_QUESTIONS.md](docs/INTERVIEW_QUESTIONS.md):** interview questions about this project, with answers.

The Markdown files are the source. `tools/make_pdfs.py` turns them into the PDFs and draws the diagrams (instructions are at the top of the script).

## Project layout

```
payrecon/
├── payrecon                 CLI launcher (builds automatically)
├── run-demo.sh              one-command demo
├── docker-compose.yml       MySQL 8.4, MongoDB 7, Hive 4.0.1
├── docs/                    explanations (Markdown + PDF), build plan, interview questions
├── tools/make_pdfs.py       renders the PDFs and their diagrams from the Markdown
├── hive/                    HiveQL: table definitions + 5 reports
├── src/main/java/com/payrecon/
│   ├── ledger/              double-entry ledger, audit
│   ├── api/                 REST API + concurrency stress test
│   ├── bank/                the ONE parser for all three bank formats
│   ├── recon/match/         matching rules as pure functions (unit-tested)
│   ├── recon/mr/            the 3 MapReduce jobs
│   ├── recon/               orchestrator, cash application, tie-out, verifier
│   ├── exceptions/          analysts' queue (SKIP LOCKED, optimistic locking)
│   ├── mongo/  hive/        MongoDB store, Hive runner
│   └── gen/                 deterministic data generator + answer key
└── src/main/resources/sql/  MySQL schema (15 tables)
```
