-- SETTLEMENT LAG: how many days each bank takes to pay us after a customer pays.
-- percentile_approx gives the 95th percentile: 95% of settlements were at least this fast.
-- Usage: --hivevar run_id=N
USE payrecon;

SELECT bank,
       COUNT(*)                                                 AS settled_payments,
       ROUND(AVG(lag_days), 2)                                  AS avg_lag_days,
       MIN(lag_days)                                            AS min_lag,
       MAX(lag_days)                                            AS max_lag,
       percentile_approx(CAST(lag_days AS DOUBLE), 0.95)        AS p95_lag_days
FROM (
  SELECT bank, DATEDIFF(bank_date, ledger_date) AS lag_days
  FROM recon_matches
  WHERE run_id = ${hivevar:run_id} AND match_pass <> 'PAYOUT_BATCH'
) t
GROUP BY bank
ORDER BY bank;

SELECT bank, lag_days, COUNT(*) AS payments
FROM (
  SELECT bank, DATEDIFF(bank_date, ledger_date) AS lag_days
  FROM recon_matches
  WHERE run_id = ${hivevar:run_id} AND match_pass <> 'PAYOUT_BATCH'
) t
GROUP BY bank, lag_days
ORDER BY bank, lag_days;
