-- AGING: how old are the payments the banks still owe us? Old items are the worrying ones.
-- Usage: --hivevar run_id=N --hivevar through=YYYY-MM-DD
USE payrecon;

SELECT bank,
       exception_type,
       SUM(CASE WHEN age <= 2               THEN 1 ELSE 0 END) AS `0_2_days`,
       SUM(CASE WHEN age BETWEEN 3 AND 7    THEN 1 ELSE 0 END) AS `3_7_days`,
       SUM(CASE WHEN age BETWEEN 8 AND 15   THEN 1 ELSE 0 END) AS `8_15_days`,
       SUM(CASE WHEN age > 15               THEN 1 ELSE 0 END) AS `over_15_days`,
       COUNT(*)                                                AS items,
       ROUND(SUM(ledger_amount_minor) / 100.0, 2)              AS amount_inr
FROM (
  SELECT bank, exception_type, ledger_amount_minor,
         DATEDIFF('${hivevar:through}', ledger_date) AS age
  FROM recon_exceptions
  WHERE run_id = ${hivevar:run_id}
    AND exception_type IN ('IN_TRANSIT', 'MISSING_IN_BANK', 'PENDING_REVIEW')
) t
GROUP BY bank, exception_type
ORDER BY bank, exception_type;
