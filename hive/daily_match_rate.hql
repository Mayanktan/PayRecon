-- DAILY MATCH RATE: for each capture date, how many payments were matched automatically and by which pass.
-- running_payments uses a window function with a frame: "sum from the first row up to this row".
-- Usage: --hivevar run_id=N
USE payrecon;

SELECT capture_date,
       payments,
       exact_ref,
       window_match,
       open_items,
       ROUND(100.0 * (exact_ref + window_match) / payments, 2) AS auto_match_pct,
       SUM(payments) OVER (ORDER BY capture_date ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_payments
FROM (
  SELECT l.capture_date,
         COUNT(*)                                                      AS payments,
         SUM(CASE WHEN m.match_pass = 'EXACT_REF'          THEN 1 ELSE 0 END) AS exact_ref,
         SUM(CASE WHEN m.match_pass = 'AMOUNT_DATE_WINDOW' THEN 1 ELSE 0 END) AS window_match,
         SUM(CASE WHEN m.match_pass IS NULL                THEN 1 ELSE 0 END) AS open_items
  FROM ledger_payments l
  LEFT JOIN (
    SELECT ledger_ref, match_pass
    FROM recon_matches
    WHERE run_id = ${hivevar:run_id} AND match_pass <> 'PAYOUT_BATCH'
  ) m ON m.ledger_ref = l.payment_id
  WHERE l.run_id = ${hivevar:run_id}
  GROUP BY l.capture_date
) daily
ORDER BY capture_date;
