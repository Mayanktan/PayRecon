-- PayRecon Hive warehouse tables. Safe to run many times (IF NOT EXISTS).
-- All tables are EXTERNAL: Hive only reads the files the Java app writes under ./data/warehouse
-- (mounted at /data inside the container). Dropping a table never deletes our files.
-- Text files, tab-separated; \N means NULL (Hive's default for text tables).

CREATE DATABASE IF NOT EXISTS payrecon;
USE payrecon;

CREATE EXTERNAL TABLE IF NOT EXISTS ledger_payments (
  payment_id     STRING,
  merchant_id    STRING,
  acquirer       STRING,
  amount_minor   BIGINT,
  capture_date   STRING
)
PARTITIONED BY (run_id INT)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
STORED AS TEXTFILE
LOCATION 'file:///data/warehouse/ledger_payments';

CREATE EXTERNAL TABLE IF NOT EXISTS bank_lines (
  line_id        STRING,
  bank           STRING,
  direction      STRING,
  ref            STRING,
  ref_quality    STRING,
  amount_minor   BIGINT,
  bank_txn_id    STRING
)
PARTITIONED BY (value_date STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
STORED AS TEXTFILE
LOCATION 'file:///data/warehouse/bank_lines';

CREATE EXTERNAL TABLE IF NOT EXISTS recon_matches (
  match_pass           STRING,
  ledger_ref           STRING,
  bank_line_id         STRING,
  bank                 STRING,
  merchant_id          STRING,
  ledger_amount_minor  BIGINT,
  bank_amount_minor    BIGINT,
  ledger_date          STRING,
  bank_date            STRING
)
PARTITIONED BY (run_id INT)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
STORED AS TEXTFILE
LOCATION 'file:///data/warehouse/recon_matches';

CREATE EXTERNAL TABLE IF NOT EXISTS recon_exceptions (
  exception_type       STRING,
  actionable           BOOLEAN,
  ledger_ref           STRING,
  bank_line_id         STRING,
  bank                 STRING,
  merchant_id          STRING,
  ledger_amount_minor  BIGINT,
  bank_amount_minor    BIGINT,
  diff_minor           BIGINT,
  ledger_date          STRING,
  bank_date            STRING,
  note                 STRING
)
PARTITIONED BY (run_id INT)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\t'
STORED AS TEXTFILE
LOCATION 'file:///data/warehouse/recon_exceptions';
