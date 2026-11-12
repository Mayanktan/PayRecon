-- PayRecon MySQL schema.
-- Part 1 is the double-entry ledger (the "system of record" for money).
-- Part 2 is the reconciliation workflow (runs, exceptions, audit trail).
-- Money is always BIGINT in minor units (paise). Never FLOAT or DOUBLE.

-- ===================== Part 1: ledger =====================

CREATE TABLE IF NOT EXISTS merchant (
  merchant_id   VARCHAR(16)  NOT NULL,
  name          VARCHAR(100) NOT NULL,
  fee_bps       INT          NOT NULL,
  payout_cycle  ENUM('DAILY','WEEKLY') NOT NULL,
  payout_bank   ENUM('HDFC','ICICI')   NOT NULL,
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (merchant_id),
  CONSTRAINT chk_fee_bps CHECK (fee_bps BETWEEN 0 AND 10000)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS account (
  account_id     BIGINT       NOT NULL AUTO_INCREMENT,
  code           VARCHAR(64)  NOT NULL,
  type           ENUM('ASSET','LIABILITY','REVENUE','EXPENSE') NOT NULL,
  merchant_id    VARCHAR(16)  NULL,
  balance_minor  BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (account_id),
  UNIQUE KEY uq_account_code (code),
  CONSTRAINT fk_account_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS journal_entry (
  entry_id      BIGINT       NOT NULL AUTO_INCREMENT,
  entry_type    ENUM('PAYMENT_CAPTURED','REFUND','PAYOUT','SETTLEMENT_RECEIVED','UNIDENTIFIED_RECEIPT',
                     'SUSPENSE_CLEARED','MOVED_TO_SUSPENSE') NOT NULL,
  external_ref  VARCHAR(64)  NOT NULL,
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (entry_id),
  UNIQUE KEY uq_entry_business_event (entry_type, external_ref)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS posting (
  posting_id    BIGINT  NOT NULL AUTO_INCREMENT,
  entry_id      BIGINT  NOT NULL,
  account_id    BIGINT  NOT NULL,
  direction     ENUM('DEBIT','CREDIT') NOT NULL,
  amount_minor  BIGINT  NOT NULL,
  PRIMARY KEY (posting_id),
  KEY idx_posting_entry (entry_id),
  KEY idx_posting_account (account_id),
  CONSTRAINT chk_posting_positive CHECK (amount_minor > 0),
  CONSTRAINT fk_posting_entry   FOREIGN KEY (entry_id)   REFERENCES journal_entry (entry_id),
  CONSTRAINT fk_posting_account FOREIGN KEY (account_id) REFERENCES account (account_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payment (
  payment_id      VARCHAR(20)  NOT NULL,
  merchant_id     VARCHAR(16)  NOT NULL,
  amount_minor    BIGINT       NOT NULL,
  fee_minor       BIGINT       NOT NULL,
  refunded_minor  BIGINT       NOT NULL DEFAULT 0,
  method          ENUM('UPI','CARD','NETBANKING') NOT NULL,
  acquirer        ENUM('HDFC','ICICI','CARDNET')  NOT NULL,
  captured_at     DATETIME(3)  NOT NULL,
  payout_id       VARCHAR(40)  NULL,
  entry_id        BIGINT       NOT NULL,
  PRIMARY KEY (payment_id),
  KEY idx_payment_captured (captured_at),
  KEY idx_payment_payout (payout_id),
  KEY idx_payment_merchant (merchant_id),
  CONSTRAINT chk_payment_amount CHECK (amount_minor > 0 AND fee_minor >= 0 AND fee_minor < amount_minor),
  CONSTRAINT chk_payment_refund_limit CHECK (refunded_minor >= 0 AND refunded_minor <= amount_minor),
  CONSTRAINT fk_payment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
  CONSTRAINT fk_payment_entry    FOREIGN KEY (entry_id)    REFERENCES journal_entry (entry_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS refund (
  refund_id     VARCHAR(24)  NOT NULL,
  payment_id    VARCHAR(20)  NOT NULL,
  amount_minor  BIGINT       NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  payout_id     VARCHAR(40)  NULL,
  entry_id      BIGINT       NOT NULL,
  PRIMARY KEY (refund_id),
  KEY idx_refund_payment (payment_id),
  CONSTRAINT chk_refund_amount CHECK (amount_minor > 0),
  CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
  CONSTRAINT fk_refund_entry   FOREIGN KEY (entry_id)   REFERENCES journal_entry (entry_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payout_batch (
  batch_id      VARCHAR(40)  NOT NULL,
  bank          ENUM('HDFC','ICICI') NOT NULL,
  payout_date   DATE         NOT NULL,
  total_minor   BIGINT       NOT NULL,
  payout_count  INT          NOT NULL,
  PRIMARY KEY (batch_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payout (
  payout_id     VARCHAR(40)  NOT NULL,
  merchant_id   VARCHAR(16)  NOT NULL,
  batch_id      VARCHAR(40)  NOT NULL,
  amount_minor  BIGINT       NOT NULL,
  payout_date   DATE         NOT NULL,
  entry_id      BIGINT       NOT NULL,
  PRIMARY KEY (payout_id),
  KEY idx_payout_date (payout_date),
  CONSTRAINT chk_payout_amount CHECK (amount_minor > 0),
  CONSTRAINT fk_payout_merchant FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id),
  CONSTRAINT fk_payout_batch    FOREIGN KEY (batch_id)    REFERENCES payout_batch (batch_id),
  CONSTRAINT fk_payout_entry    FOREIGN KEY (entry_id)    REFERENCES journal_entry (entry_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS idempotency_key (
  idem_key       VARCHAR(100) NOT NULL,
  request_hash   CHAR(64)     NOT NULL,
  response_code  INT          NOT NULL,
  response_body  TEXT         NOT NULL,
  created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (idem_key)
) ENGINE=InnoDB;

-- Cash application, one row per bank credit line: where its money was booked in the ledger.
-- CLEARING = matched to a payment (the bank paid what it owed); SUSPENSE = received but not identified yet.
-- Each run compares this table with its new results and books only the CHANGES, so re-runs never double-book,
-- and a line identified later is moved from SUSPENSE to CLEARING ("clearing the suspense account").
CREATE TABLE IF NOT EXISTS cash_application (
  bank_line_id  VARCHAR(100) NOT NULL,
  bank          VARCHAR(16)  NOT NULL,
  value_date    DATE         NOT NULL,
  amount_minor  BIGINT       NOT NULL,
  bucket        ENUM('CLEARING','SUSPENSE') NOT NULL,
  run_id        INT          NOT NULL,
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (bank_line_id),
  KEY idx_cash_bank_date (bank, value_date, bucket)
) ENGINE=InnoDB;

-- ===================== Part 2: reconciliation workflow =====================

CREATE TABLE IF NOT EXISTS recon_run (
  run_id                 INT          NOT NULL AUTO_INCREMENT,
  period                 CHAR(7)      NOT NULL,
  through_date           DATE         NOT NULL,
  status                 ENUM('RUNNING','COMPLETED','FAILED') NOT NULL,
  started_at             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at            TIMESTAMP(3) NULL,
  runtime_ms             BIGINT       NULL,
  ledger_payments        INT          NULL,
  ledger_payouts         INT          NULL,
  bank_files             INT          NULL,
  bank_lines             INT          NULL,
  malformed_lines        INT          NULL,
  matched                INT          NULL,
  exceptions_total       INT          NULL,
  exceptions_actionable  INT          NULL,
  PRIMARY KEY (run_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recon_pass_stat (
  run_id        INT          NOT NULL,
  pass_name     VARCHAR(32)  NOT NULL,
  matched       INT          NOT NULL,
  amount_minor  BIGINT       NOT NULL,
  PRIMARY KEY (run_id, pass_name),
  CONSTRAINT fk_pass_run FOREIGN KEY (run_id) REFERENCES recon_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recon_step_timing (
  run_id   INT          NOT NULL,
  step     VARCHAR(40)  NOT NULL,
  millis   BIGINT       NOT NULL,
  PRIMARY KEY (run_id, step),
  CONSTRAINT fk_timing_run FOREIGN KEY (run_id) REFERENCES recon_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS recon_exception (
  exception_id         BIGINT       NOT NULL AUTO_INCREMENT,
  run_id               INT          NOT NULL,
  exception_type       VARCHAR(32)  NOT NULL,
  actionable           BOOLEAN      NOT NULL,
  ledger_ref           VARCHAR(40)  NULL,
  bank_line_id         VARCHAR(100) NULL,
  bank                 VARCHAR(16)  NULL,
  merchant_id          VARCHAR(16)  NULL,
  ledger_amount_minor  BIGINT       NULL,
  bank_amount_minor    BIGINT       NULL,
  diff_minor           BIGINT       NULL,
  ledger_date          DATE         NULL,
  bank_date            DATE         NULL,
  note                 VARCHAR(255) NULL,
  status               ENUM('OPEN','INVESTIGATING','RESOLVED') NOT NULL DEFAULT 'OPEN',
  assigned_to          VARCHAR(40)  NULL,
  resolution           VARCHAR(32)  NULL,
  resolution_note      VARCHAR(255) NULL,
  version              INT          NOT NULL DEFAULT 0,
  updated_at           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (exception_id),
  KEY idx_exception_queue (run_id, status, actionable),
  KEY idx_exception_type (run_id, exception_type),
  KEY idx_exception_ledger_ref (ledger_ref),
  CONSTRAINT fk_exception_run FOREIGN KEY (run_id) REFERENCES recon_run (run_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS exception_event (
  event_id      BIGINT       NOT NULL AUTO_INCREMENT,
  exception_id  BIGINT       NOT NULL,
  actor         VARCHAR(40)  NOT NULL,
  action        VARCHAR(32)  NOT NULL,
  detail        VARCHAR(255) NULL,
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (event_id),
  KEY idx_event_exception (exception_id),
  CONSTRAINT fk_event_exception FOREIGN KEY (exception_id) REFERENCES recon_exception (exception_id) ON DELETE CASCADE
) ENGINE=InnoDB;
