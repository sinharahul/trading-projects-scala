-- Postgres ledger mirror. Primary key makes re-mirroring idempotent.
CREATE TABLE IF NOT EXISTS ledger_entries (
  business_date DATE NOT NULL,
  account_key BIGINT NOT NULL,
  entry_id TEXT NOT NULL,
  account_code TEXT NOT NULL,
  debit_cents BIGINT NOT NULL,
  credit_cents BIGINT NOT NULL,
  reference TEXT,
  narrative TEXT,
  PRIMARY KEY (business_date, entry_id)
);
