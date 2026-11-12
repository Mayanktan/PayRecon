-- EXCEPTION BREAKDOWN: what went wrong, where, and which merchants are most affected.
-- Usage: --hivevar run_id=N
USE payrecon;

SELECT exception_type,
       bank,
       IF(actionable, 'yes', 'no') AS needs_action,
       COUNT(*)                    AS items,
       ROUND(SUM(ABS(COALESCE(diff_minor, ledger_amount_minor, bank_amount_minor, 0))) / 100.0, 2) AS amount_inr
FROM recon_exceptions
WHERE run_id = ${hivevar:run_id}
GROUP BY exception_type, bank, actionable
ORDER BY needs_action DESC, items DESC;

-- Top 10 merchants by money stuck in actionable exceptions. RANK() is a window function:
-- it numbers rows by the ORDER BY inside OVER (...), giving equal values the same rank.
SELECT rnk AS rank_no, merchant_id, items, amount_inr
FROM (
  SELECT merchant_id,
         items,
         ROUND(amount_minor / 100.0, 2)            AS amount_inr,
         RANK() OVER (ORDER BY amount_minor DESC)  AS rnk
  FROM (
    SELECT merchant_id,
           COUNT(*)                                                  AS items,
           SUM(ABS(COALESCE(diff_minor, ledger_amount_minor, 0)))    AS amount_minor
    FROM recon_exceptions
    WHERE run_id = ${hivevar:run_id} AND actionable = true AND merchant_id IS NOT NULL
    GROUP BY merchant_id
  ) per_merchant
) ranked
WHERE rnk <= 10
ORDER BY rank_no;
