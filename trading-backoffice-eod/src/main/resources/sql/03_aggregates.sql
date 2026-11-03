CREATE TABLE IF NOT EXISTS gold.agg_daily_volume (
  business_date   DATE,
  security_key    BIGINT,
  ticker          STRING,
  total_shares    BIGINT,
  total_value_cents BIGINT,
  trade_count     BIGINT
) USING DELTA PARTITIONED BY (business_date);

CREATE TABLE IF NOT EXISTS gold.agg_desk_pnl (
  business_date   DATE,
  desk            STRING,
  realized_pnl_cents BIGINT,
  unrealized_pnl_cents BIGINT,
  commission_cents BIGINT,
  fee_cents       BIGINT,
  net_pnl_cents   BIGINT
) USING DELTA PARTITIONED BY (business_date);

CREATE TABLE IF NOT EXISTS gold.agg_top_symbols (
  business_date   DATE,
  rank            INT,
  ticker          STRING,
  total_shares    BIGINT,
  total_value_cents BIGINT
) USING DELTA PARTITIONED BY (business_date);
