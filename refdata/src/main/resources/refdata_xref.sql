CREATE TABLE refdata_xref (
  id_type       VARCHAR(20)  NOT NULL,
  id_value      VARCHAR(40)  NOT NULL,
  golden_key    VARCHAR(36)  NOT NULL,
  source        VARCHAR(20)  NOT NULL,
  valid_from    DATE         NOT NULL,
  valid_to      DATE,
  PRIMARY KEY (id_type, id_value, valid_from)
);
CREATE INDEX idx_xref_golden ON refdata_xref (golden_key);
CREATE INDEX idx_xref_value ON refdata_xref (id_type, id_value);
