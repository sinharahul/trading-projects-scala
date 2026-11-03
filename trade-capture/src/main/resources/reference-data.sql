CREATE TABLE IF NOT EXISTS securities (
  symbol        VARCHAR(16) PRIMARY KEY,
  exchange      VARCHAR(16) NOT NULL,
  currency      VARCHAR(3)  NOT NULL,
  tick_size     NUMERIC(18, 6) NOT NULL,
  reference_px  NUMERIC(18, 6) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS counterparties (
  counterparty_id VARCHAR(32) PRIMARY KEY,
  name            VARCHAR(128) NOT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'APPROVED'
);

CREATE TABLE IF NOT EXISTS trading_calendar (
  exchange     VARCHAR(16) NOT NULL,
  trade_date   DATE NOT NULL,
  is_trading_day BOOLEAN NOT NULL,
  session_open TIME NOT NULL,
  session_close TIME NOT NULL,
  PRIMARY KEY (exchange, trade_date)
);

CREATE TABLE IF NOT EXISTS rejected_trades (
  exec_id        VARCHAR(64) PRIMARY KEY,
  received_at    TIMESTAMP NOT NULL DEFAULT NOW(),
  raw_message    TEXT NOT NULL,
  rejection_code VARCHAR(64) NOT NULL,
  rejection_detail TEXT,
  replayed       BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO securities (symbol, exchange, currency, tick_size, reference_px, status)
VALUES
  ('AAPL', 'NASDAQ', 'USD', 0.01, 227.50, 'ACTIVE'),
  ('MSFT', 'NASDAQ', 'USD', 0.01, 428.10, 'ACTIVE'),
  ('JPM',  'NYSE',   'USD', 0.01, 248.30, 'ACTIVE'),
  ('TSLA', 'NASDAQ', 'USD', 0.01, 251.80, 'ACTIVE'),
  ('DEAD', 'NYSE',   'USD', 0.01, 10.00,  'DELISTED')
ON CONFLICT (symbol) DO NOTHING;

INSERT INTO counterparties (counterparty_id, name, status)
VALUES
  ('EXCHANGE_X', 'Exchange X', 'APPROVED'),
  ('EXCHANGE_Y', 'Exchange Y', 'APPROVED'),
  ('BROKER_B',   'Broker B',   'SUSPENDED')
ON CONFLICT (counterparty_id) DO NOTHING;

INSERT INTO trading_calendar (exchange, trade_date, is_trading_day, session_open, session_close)
VALUES
  ('NASDAQ', DATE '2026-10-30', TRUE, '09:30:00', '16:00:00'),
  ('NYSE',   DATE '2026-10-30', TRUE, '09:30:00', '16:00:00'),
  ('NASDAQ', DATE '2026-10-31', FALSE, '09:30:00', '16:00:00'),
  ('NYSE',   DATE '2026-10-31', FALSE, '09:30:00', '16:00:00')
ON CONFLICT (exchange, trade_date) DO NOTHING;
