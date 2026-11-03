-- dim_date: one row per calendar date, 2025-01-01 through 2035-12-31.
-- Populated by a generator job; shown here as DDL only.
CREATE TABLE IF NOT EXISTS gold.dim_date (
  date_key        INT,
  calendar_date   DATE,
  year            INT,
  quarter         INT,
  month           INT,
  day             INT,
  day_of_week     INT,
  is_business_day BOOLEAN,
  is_month_end    BOOLEAN,
  is_quarter_end  BOOLEAN,
  is_year_end     BOOLEAN,
  prev_business_date DATE,
  next_business_date DATE
) USING DELTA
PARTITIONED BY (year);

-- dim_security: one row per security, SCD Type 1 (current attributes).
CREATE TABLE IF NOT EXISTS gold.dim_security (
  security_key    BIGINT,
  ticker          STRING,
  isin            STRING,
  security_name   STRING,
  exchange        STRING,
  currency        STRING,
  asset_class     STRING,
  is_active       BOOLEAN,
  updated_date    DATE
) USING DELTA;

-- dim_account: one row per account, SCD Type 1.
CREATE TABLE IF NOT EXISTS gold.dim_account (
  account_key     BIGINT,
  account_number  STRING,
  account_name    STRING,
  desk            STRING,
  account_type    STRING,
  base_currency   STRING,
  is_active       BOOLEAN,
  updated_date    DATE
) USING DELTA;
