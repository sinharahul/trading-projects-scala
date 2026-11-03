-- fact_trades: one row per final trade.
CREATE TABLE IF NOT EXISTS gold.fact_trades (
  trade_key       BIGINT,
  business_date   DATE,
  account_key     BIGINT,
  security_key    BIGINT,
  trade_id        STRING,
  side            STRING,
  quantity        BIGINT,
  price_cents     BIGINT,
  amount_cents    BIGINT,
  commission_cents BIGINT,
  fee_cents       BIGINT,
  currency        STRING,
  execution_time  TIMESTAMP,
  is_correction   BOOLEAN,
  is_cancelled    BOOLEAN
) USING DELTA
PARTITIONED BY (business_date);

-- fact_positions: one row per account per security per day.
CREATE TABLE IF NOT EXISTS gold.fact_positions (
  business_date   DATE,
  account_key     BIGINT,
  security_key    BIGINT,
  quantity        BIGINT,
  avg_cost_cents  BIGINT,
  market_price_cents BIGINT,
  market_value_cents BIGINT,
  unrealized_pnl_cents BIGINT
) USING DELTA
PARTITIONED BY (business_date);

-- fact_daily_pnl: one row per account per security per day.
CREATE TABLE IF NOT EXISTS gold.fact_daily_pnl (
  business_date   DATE,
  account_key     BIGINT,
  security_key    BIGINT,
  realized_pnl_cents BIGINT,
  unrealized_pnl_cents BIGINT,
  commission_cents BIGINT,
  fee_cents       BIGINT,
  net_pnl_cents   BIGINT
) USING DELTA
PARTITIONED BY (business_date);
