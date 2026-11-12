# PayRecon: interview questions and answers

These are the questions interviewers are likely to ask about this project, with answers based on what the code actually does. Learn the ideas, not the exact sentences. Each answer ends where a natural follow-up begins, so be ready to go one level deeper.

---

## A. Pitch and resume

**30-second pitch**
> "I built PayRecon, a working miniature of the ledger and reconciliation systems payment companies like Stripe run. Payments, refunds and payouts are booked in a double-entry ledger in MySQL, behind a REST API with idempotency keys, so retries never charge twice. Every month, a three-job Hadoop MapReduce pipeline matches 200,000 ledger payments against settlement files from three banks in three formats. Hive produces the month-end close, and MongoDB keeps the raw bank records and merchant statements. I proved it correct against planted ground truth: every planted problem was caught with zero false alarms, and the ledger ties out to the paisa for every bank."

**Resume bullets**
- Built a payments ledger and bank-reconciliation system (Java 17, MySQL, Hadoop MapReduce, Hive, MongoDB). A double-entry ledger with idempotent REST APIs survived 50 concurrent duplicate requests (1 charge) and 20 racing refunds (no over-refund).
- Designed a 3-job MapReduce matching engine (reduce-side join, secondary sort, combiner) that reconciles 200k payments across 3 bank formats in 22 s end to end. It auto-matches 99.8% of due payments and flags 100% of 1,072 planted discrepancies, with 0 false positives: 94% are classified automatically and 6% are routed to human review instead of being guessed.
- Implemented cash application with per-line idempotency, a suspense-clearing flow and a ledger tie-out per bank. Built a Hive analytics layer (partitions, window functions) and a MongoDB read model for merchant statements.

---

## B. About the project

**Q1. Why did you build this?**
I wanted a project that exercises real backend fundamentals rather than just CRUD: transactions, concurrency, idempotency and data processing at scale. Payment reconciliation is a real problem every fintech has, and it is easy to explain: money we expected versus money we got.

**Q2. Walk me through the architecture.**
Four parts:
1. **The ledger and API in MySQL.** Every money movement is a balanced journal entry.
2. **A generator** that creates a month of payments plus the files three banks send, with known problems planted and an answer key.
3. **The reconcile command**, in six steps:
   1. extract the ledger to files
   2. run 3 MapReduce jobs to match
   3. load exceptions into MySQL
   4. book the cash into the ledger
   5. publish files to Hive
   6. upsert raw records and statements into MongoDB
4. **Verification:** `verify` against the answer key, the tie-out, the ledger audit, and the stress test.

**Q3. What was the hardest part?**
Correctness across repeated runs. My first cash-application design booked one entry per bank per day. When a later run matched a line that was ambiguous before, that day counted as "already booked", so ₹2,000 stayed in suspense and the tie-out showed a difference. I redesigned it around a per-bank-line record (`cash_application`). Each run books only the changes, including a "suspense cleared" entry when a line gets identified. It made me appreciate why real ledgers track state at the finest grain.

**Q4. What would you do differently in production?**
- Run the matching on Spark or a real cluster.
- Add a connection pool (HikariCP).
- Shard the hot accounts.
- Handle multi-currency and payments from earlier months.
- Stream events through Kafka instead of daily files.
- Add authentication and PCI-DSS controls.
- Add monitoring and alerting on the tie-out result.

**Q5. How big is the data, and how fast is it?**
- 200,000 payments (₹25 crore), 105 bank files, 200k lines.
- Generating the month takes 13 s.
- Reconciling takes 21.9 s end to end, of which MapReduce is 7.7 s and MongoDB 7.4 s, on a laptop with Hadoop in local mode.

---

## C. Ledger and double-entry

**Q6. Why double-entry? Isn't a balance column enough?**
A balance column cannot prove itself. In double-entry, every movement has equal debits and credits, so "total debits = total credits" and "assets = liabilities + revenue − expenses" must always hold. One SQL query catches a bug anywhere in the system. My `audit` command runs 9 such checks.

**Q7. Walk me through the entries for a ₹1,000 payment with a 2% fee.**
1. **Capture:** debit `CLEARING:HDFC` 1,000; credit `MERCHANT_PAYABLE` 980; credit `FEE_REVENUE` 20.
2. **The bank settles:** debit `SETTLEMENT_CASH` 1,000; credit `CLEARING:HDFC` 1,000.
3. **Payout:** debit `MERCHANT_PAYABLE` 980; credit `SETTLEMENT_CASH` 980.

**Q8. Why store money as paise in a long?**
Binary floating point can't represent most decimals exactly (0.1 + 0.2 ≠ 0.3), and the errors add up. Integers in minor units are exact. Bank rupee strings are parsed with `BigDecimal`, which rejects more than two decimals, and fees use integer arithmetic with half-up rounding.

**Q9. What is a suspense account and when do you use it?**
It is where you park money you received but can't identify yet. Unknown credits, duplicate settlements and ambiguous lines go there. When a later run identifies a line, PayRecon moves it with "debit SUSPENSE, credit CLEARING". That is called clearing the suspense account.

**Q10. How do you know the stored balances are right?**
Balances are updated in the same transaction as the postings, and the audit recomputes every balance from its postings and compares them. There are also database constraints: posting amounts must be positive, refunds can't exceed the payment, foreign keys must match, and one business event can only be booked once (a unique key on entry type plus reference).

**Q11. What is a "hot account" problem?**
Every payment updates `FEE_REVENUE` and the clearing account. Under heavy load, all transactions queue on those same rows. The fixes are:
- split the account into N sub-accounts and sum them when reading, or
- append postings only and compute balances asynchronously.

---

## D. Transactions, concurrency and idempotency

**Q12. How does your idempotency work?**
The client sends an `Idempotency-Key` header. In one transaction I:
1. insert the key (it is the primary key) together with a hash of the request
2. book the payment
3. store the response
4. commit

A retry hits "duplicate key", reads the stored response and returns it with `Idempotent-Replayed: true`. The same key with a different body returns 422.

**Q13. What if two identical requests arrive at exactly the same time?**
The first one inserts the key and holds a row lock. The second one's insert blocks on that lock. When the first commits, the second gets the duplicate-key error and replays the stored response. The stress test sends 50 at once: 1 created, 49 replayed, 1 row in the database.

**Q14. Why store the key in the same transaction as the payment?**
Then they succeed or fail together. If the key were saved separately, a crash in between could leave a key with no payment (the retry is wrongly refused), or a payment with no key (the retry double-charges).

**Q15. How did you prevent over-refunding?**
- `SELECT … FOR UPDATE` on the payment row, so a concurrent refund waits and then reads the updated total. This is pessimistic locking.
- A CHECK constraint `refunded_minor <= amount_minor` as a last line of defence.

The stress test fires 20 refunds of ₹100 on a ₹1,000 payment: exactly 10 succeed.

**Q16. Optimistic versus pessimistic locking: where do you use each?**
- **Pessimistic** (lock first) for refunds, because conflicts are likely and the check must be exact.
- **Optimistic** (a version column, `UPDATE … WHERE version = ?`) for resolving exceptions. Conflicts are rare, so we detect them instead of holding locks.

**Q17. How do you avoid deadlocks?**
1. **Consistent lock order:** account balances are updated sorted by `account_id`.
2. **Update balances before inserting postings.** The foreign-key check on a posting takes a shared lock on the account, and upgrading that to an exclusive lock later is a classic deadlock.
3. **Automatic retry** with backoff on MySQL errors 1213 and 1205.

**Q18. Which isolation level, and why?**
READ COMMITTED. Correctness comes from explicit locks (`FOR UPDATE`) and constraints, not from the isolation level, and in InnoDB READ COMMITTED takes fewer gap locks than REPEATABLE READ, so there are fewer deadlocks.

**Q19. How does the analysts' queue avoid giving two people the same item?**
`SELECT … ORDER BY exception_id LIMIT 1 FOR UPDATE SKIP LOCKED`. Each analyst locks the next free row and skips rows others hold.

The key detail: the `ORDER BY` must follow an index (`run_id, status, actionable`, plus the primary key). Otherwise MySQL sorts first, and that means reading and locking every matching row. I insert exceptions in priority order so that id order is priority order.

**Q20. Explain ACID with an example from your project.**
- **Atomic:** a payment's entry, its postings, the balance updates and the idempotency key all commit together.
- **Consistent:** constraints and balanced entries always hold.
- **Isolated:** concurrent refunds don't see each other's half-written state.
- **Durable:** after commit, a crash doesn't lose the payment.

---

## E. Reconciliation logic

**Q21. Why three passes instead of just joining on reference?**
Real bank data is messy. Pass 1 (exact reference) handles 99%+. Pass 2 handles lines whose reference was cut short or is missing, by matching the same bank, the exact amount and a nearby date. Pass 3 handles payouts, where one bank debit equals a whole batch of payouts (many-to-one).

**Q22. Why not just match by amount and date for everything?**
Round amounts collide: many ₹500 UPI payments happen every day. Amount and date are a fallback, used only when there is exactly one candidate.

**Q23. What happens when a bank line fits several payments?**
It becomes `AMBIGUOUS_MATCH`, and the candidates become `PENDING_REVIEW`. The engine never guesses with money, because a wrong automatic match hides a real problem. In the 200k run, 59 of 585 short-reference lines were sent to review and 526 were auto-matched.

**Q24. What's the difference between in transit and missing?**
Banks settle one or two days after capture. A payment younger than the normal lag plus a 2-day grace period is `IN_TRANSIT`, which is not an error. Older than that, it is `MISSING_IN_BANK`. At the 31 October cutoff, 7,837 payments were in transit. By the 5 November run they had all settled.

**Q25. What is the tie-out, and why does it matter?**
For each bank: captured − cash booked must equal the open items (in transit + missing + in review + amounts short-paid). It connects the ledger, the bank files and the MapReduce output, so a bug in any of them shows up as a difference. That is exactly how I found the suspense bug.

**Q26. How do you know the matching is correct?**
- The generator plants known problems and writes an answer key, and `verify` checks that every planted problem is reported and nothing else is. The result was 1,072 planted, 0 missed, 0 false alarms.
- The matching rules are pure functions with unit tests, including edge cases like line 10 sorting before line 9 as text.

**Q27. Is re-running a reconciliation safe?**
Yes. Each run gets a new id. Cash application compares with the per-line `cash_application` record and books only the changes, and the ledger entry references are unique per run.

---

## F. MapReduce

**Q28. Explain MapReduce simply.**
Map turns each record into key-value pairs. The framework shuffles and sorts, so all values for a key reach one reducer. Reduce processes each key's group. It scales by adding machines, because groups are independent.

**Q29. What is a reduce-side join, and where do you use it?**
Job 1's mapper reads both the ledger and the bank files, tags each record (L or B), and emits it keyed by the payment reference. The reducer receives a payment and all its bank lines together, then decides: matched, mismatch, duplicate, missing.

**Q30. What is secondary sort, and why did you need it?**
In job 2, I need each bank+amount group's records sorted by date to run a sliding window. The key is `(bank|amount, date, kind, id)`:
- A custom partitioner and grouping comparator use only `bank|amount`.
- The full key controls sorting.

So each `reduce()` call gets one group, already sorted by date, and the reducer never sorts in memory.

**Q31. What does your combiner do, and when is a combiner safe?**
Job 3 sums payout amounts per batch on the map side, so it ships one partial sum instead of hundreds of records. It is safe only for associative and commutative operations like sum or count, because Hadoop may run the combiner zero, one or many times. My combiner's output format equals its input format for exactly that reason.

**Q32. Why a non-splittable input format?**
Each bank line's id includes its physical line number. Hadoop can split a file across mappers, and a mapper that starts mid-file can't count lines. So each bank file is read whole by one mapper.

**Q33. How are the three jobs scheduled?**
As a small DAG: job 1 first, then jobs 2 and 3 together, because both depend only on job 1's output. Job 1 writes side outputs with MultipleOutputs, which become the inputs of jobs 2 and 3.

**Q34. Why MapReduce and not plain SQL or Spark?**
- **Not SQL:** the matching needs custom logic per group (sliding windows, ambiguity rules, many-to-one sums). That is awkward in SQL and natural in a reducer.
- **Not Spark:** honestly, at 200k rows one machine could do it, and a company today would use Spark. But Spark keeps the same model (partitioners, shuffles, sort keys), so I learned the foundations.

**Q35. What is data skew, and could it hit you?**
Skew is one key with far more data than the others, which leaves one reducer doing most of the work. In job 2, a very common amount like ₹500 at HDFC forms a big group. Fixes: add the date bucket to the partition key with overlap between buckets, or sample keys and split the hot ones.

**Q36. What are counters for?**
Cheap global tallies that every task increments: lines per bank, unreadable lines. The driver reads them after the job for the summary.

---

## G. Hive

**Q37. Why Hive, when you already have MySQL?**
To separate OLTP (live payments, in MySQL) from OLAP (big reports over history). Hive runs SQL directly over the result files without loading them into the transactional database, so a heavy month-end report can never slow down payments.

**Q38. External versus managed tables?**
An external table points at files Hive doesn't own. Dropping it keeps the files. My tables are external because the Java app writes the files.

**Q39. How do partitions help?**
`recon_matches` is partitioned by `run_id` and `bank_lines` by `value_date`, so each partition is a folder. A query filtered on `run_id = 3` reads only that folder. That is partition pruning. After adding folders I run `MSCK REPAIR TABLE … SYNC PARTITIONS` to update the metastore.

**Q40. Show me a window function you used.**
- `RANK() OVER (ORDER BY amount DESC)` for the top 10 merchants by money stuck in exceptions.
- `SUM(payments) OVER (ORDER BY capture_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)` for a running total.

**Q41. What is schema-on-read?**
The files are plain text, and Hive applies the column structure when reading them. Normal databases enforce the schema when writing.

---

## H. MongoDB

**Q42. Why MongoDB for raw bank records?**
Every bank's format is different: a 6-column CSV, 6 pipe-separated fields, and nested JSON. A future bank will have yet another shape. Documents keep each line exactly as received (original field names, nesting) plus a normalised part, without table changes. Analysts see the original record when investigating an exception.

**Q43. What is your statements collection, and what is CQRS?**
One document per payout, with its payments, fees, refunds and bank status, so a merchant's statement is one read instead of a 4-table join. CQRS means separating the write model (the normalised ledger in MySQL) from a read model shaped for queries, rebuilt from the write model.

**Q44. How do you keep MySQL and MongoDB consistent?**
MongoDB is derived data, rebuilt from MySQL every run with upserts, so it is eventually consistent. MySQL is the source of truth, and if MongoDB is lost it can be rebuilt. I'd add a version or timestamp so readers know how fresh it is.

**Q45. Which indexes did you create, and why?**
`normalized.reference` (find all lines for a payment), `(bank, normalized.value_date)` (one bank's day), `parse_status` (find unreadable lines), and `(merchant_id, payout_date desc)` for statements. Each one matches a real query.

**Q46. Isn't MongoDB a bit forced here?**
Honestly, it has the lightest role. At this size, MySQL JSON columns could work. The genuine reasons are the different shape of each bank's records and a denormalised read model. I'd say that openly.

---

## I. System design follow-ups

**Q47. How would you scale to 10 crore (100 million) payments a day?**
- Partition the ledger by merchant or time.
- Shard the hot accounts.
- Stream bank files and payment events through Kafka.
- Run the matching on Spark with partitioning by bank and day.
- Make the matching incremental (each new file matched as it arrives) instead of whole-month batches.
- Keep the idempotency keys in a store with a TTL.
- Use a columnar warehouse (Parquet or ORC) for Hive or Trino.

**Q48. What happens if a reconciliation run crashes halfway?**
The run is marked FAILED. Nothing in the ledger is half-booked, because cash application is one transaction. The next run starts fresh with a new id, and because cash application books only changes against the per-line record, re-running is safe.

**Q49. How would you make it real-time?**
Consume settlement events from a queue, keep unmatched items in a keyed state store, try an exact match when an event arrives and a windowed match on a timer, and emit exceptions as events. That is basically the same three passes, as a streaming job.

**Q50. How would you handle multiple currencies?**
Store the amount, the currency and the FX rate on each posting, keep a separate account per currency, and book FX gains and losses explicitly. Matching would compare in the settlement currency, with a tolerance for rounding.

**Q51. What would you monitor?**
- The tie-out result per bank (alert on any difference).
- The auto-match rate.
- The count of actionable exceptions and their age.
- The suspense balance over time.
- The run duration.
- API p95 latency and idempotency replay rate.

---

## J. Testing and quality

**Q52. How did you test it?**
Five layers:
1. 27 unit tests on pure functions.
2. `verify` against the planted ground truth.
3. The tie-out.
4. A 9-rule ledger audit.
5. A concurrency stress test with a start gate, so requests really collide.

**Q53. Tell me about a bug you found.**
Bank line ids look like `file:9`. Sorted as text, `file:10` comes before `file:9`, so for a duplicate settlement the second copy was treated as the original. Ordering by file and then numeric line number fixed it, and there is now a unit test for exactly that.

**Q54. Why are the matching rules separate from the Hadoop classes?**
The reducers only convert Hadoop types and call pure functions (`ExactRefMatcher`, `WindowMatcher`, `PayoutBatchMatcher`). The rules are tested in milliseconds without Hadoop, and they could move to Spark unchanged.

---

## K. Behavioural

**Q55. A trade-off you made?**
Never auto-matching ambiguous lines. It lowers the auto-match rate a little (59 lines went to review) but guarantees no wrong match hides money. In payments, false confidence is worse than a manual check.

**Q56. Something you'd improve in your previous project?**
In my NASA log ETL project:
- The runtime clock started only after the input was read.
- The consistency check compared only row counts.
- A password was committed in the code.

In PayRecon:
- The clock starts before any input is read.
- Correctness is checked against ground truth and a tie-out.
- Secrets live in a git-ignored `.env`.

**Q57. What did you learn?**
- Correctness under concurrency must be designed, not hoped for: keys, locks, lock order, constraints.
- Idempotency belongs in the same transaction as the side effect.
- Distributed processing is mostly about choosing keys: what to group by, sort by and partition by.
- Proving correctness needs independent checks (ground truth, tie-out, audit), not just "it ran".
