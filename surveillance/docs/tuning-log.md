# Surveillance tuning log

| Date       | Detector       | Parameter          | Old  | New  | Precision before | Precision after | Recall before | Recall after | Reason |
|------------|----------------|--------------------|------|------|------------------|-----------------|---------------|--------------|--------|
| 2026-09-15 | UNUSUAL_VOLUME | z-threshold        | 3.0  | 4.0  | 33%              | 100%            | 100%          | 100%         | Spurious alerts had z between 3 and 4; planted spike z > 10. Free win. |
| 2026-09-15 | MARKING_CLOSE  | min-volume-share   | 20%  | 30%  | 50%              | 100%            | 100%          | 100%         | Spurious close was 22% share; planted case 75%. Free win. |
| 2026-09-16 | WASH_TRADE     | min-repetitions    | 3    | 3    | —                | —               | —             | —            | Considered raising to 5; rejected — would miss 4-trade rings. No change. |
