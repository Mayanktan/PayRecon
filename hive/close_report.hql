-- MONTH-END CLOSE, per bank: what we captured, what the bank actually paid, and whether the
-- difference is fully explained by open items. "TIES OUT" means every rupee is accounted for.
-- Usage: --hivevar run_id=N
USE payrecon;

WITH ledger AS (
  SELECT acquirer AS bank, COUNT(*) AS payments, SUM(amount_minor) AS captured
  FROM ledger_payments
  WHERE run_id = ${hivevar:run_id}
  GROUP BY acquirer
),
settled AS (
  SELECT bank, SUM(bank_amount_minor) AS cash_received
  FROM recon_matches
  WHERE run_id = ${hivevar:run_id} AND match_pass <> 'PAYOUT_BATCH'
  GROUP BY bank
),
open_items AS (
  SELECT bank,
         SUM(CASE WHEN exception_type = 'IN_TRANSIT'      THEN ledger_amount_minor ELSE 0 END) AS in_transit,
         SUM(CASE WHEN exception_type = 'MISSING_IN_BANK' THEN ledger_amount_minor ELSE 0 END) AS missing,
         SUM(CASE WHEN exception_type = 'PENDING_REVIEW'  THEN ledger_amount_minor ELSE 0 END) AS in_review,
         SUM(CASE WHEN exception_type = 'AMOUNT_MISMATCH' THEN diff_minor          ELSE 0 END) AS short_paid,
         SUM(CASE WHEN exception_type IN ('MISSING_IN_LEDGER', 'AMBIGUOUS_MATCH') THEN bank_amount_minor
                  WHEN exception_type = 'DUPLICATE_IN_BANK' AND ledger_ref LIKE 'pay_%' THEN bank_amount_minor
                  ELSE 0 END) AS unidentified_cash
  FROM recon_exceptions
  WHERE run_id = ${hivevar:run_id}
  GROUP BY bank
)
SELECT l.bank                                                           AS bank,
       l.payments                                                       AS payments,
       ROUND(l.captured / 100.0, 2)                                     AS captured_inr,
       ROUND(COALESCE(s.cash_received, 0) / 100.0, 2)                   AS settled_inr,
       ROUND((l.captured - COALESCE(s.cash_received, 0)) / 100.0, 2)    AS still_owed_inr,
       ROUND(COALESCE(o.in_transit, 0) / 100.0, 2)                      AS in_transit_inr,
       ROUND(COALESCE(o.missing, 0) / 100.0, 2)                         AS missing_inr,
       ROUND(COALESCE(o.in_review, 0) / 100.0, 2)                       AS in_review_inr,
       ROUND(COALESCE(o.short_paid, 0) / 100.0, 2)                      AS short_paid_inr,
       CASE WHEN l.captured - COALESCE(s.cash_received, 0)
                 = COALESCE(o.in_transit, 0) + COALESCE(o.missing, 0)
                   + COALESCE(o.in_review, 0) + COALESCE(o.short_paid, 0)
            THEN 'TIES OUT' ELSE 'DIFFERENCE' END                         AS close_status,
       ROUND(COALESCE(o.unidentified_cash, 0) / 100.0, 2)               AS suspense_inr
FROM ledger l
LEFT JOIN settled s    ON s.bank = l.bank
LEFT JOIN open_items o ON o.bank = l.bank
ORDER BY l.bank;
