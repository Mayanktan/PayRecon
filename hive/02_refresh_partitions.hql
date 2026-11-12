-- Tells Hive's metastore about partition folders the Java app added or removed
-- (e.g. recon_matches/run_id=3/ or bank_lines/value_date=2026-10-02/).
USE payrecon;
MSCK REPAIR TABLE ledger_payments SYNC PARTITIONS;
MSCK REPAIR TABLE bank_lines SYNC PARTITIONS;
MSCK REPAIR TABLE recon_matches SYNC PARTITIONS;
MSCK REPAIR TABLE recon_exceptions SYNC PARTITIONS;
