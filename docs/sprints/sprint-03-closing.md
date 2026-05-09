# Sprint 03 — Monthly Closing Job

## 배경

Sprint 01의 Job 1이 매일 만들어 둔 `daily_transaction_summaries`를 월 단위로 다시 합산해 `monthly_account_summaries`에 1행/계좌로 기록한다. 회계 보고서의 월간 잔액·매출 등 상위 보고서의 출발점.

## 입출력

- **JobParameter**: `yearMonth` (YYYY-MM, 예: `2026-05`)
- **소스 테이블**: `daily_transaction_summaries`, settlementDate가 해당 월에 속함
- **타깃 테이블**: `monthly_account_summaries`, 계좌당 1행

## Acceptance Criteria

### A. 정상 흐름

- **A1**: `yearMonth=2026-05`로 실행 시 BatchStatus=COMPLETED.
- **A2**: 생성된 monthly_account_summaries row 수 = 그 달 daily summary가 1건이라도 있는 distinct account 수.
- **A3**: 임의 계좌의 `total_deposit`은 그 달 daily summary들의 deposit 총합과 정확히 일치 (BigDecimal).
- **A4**: 임의 계좌의 `transaction_count`는 그 달 daily summary들의 tx_count 총합.
- **A5**: `net_amount = total_deposit - total_withdrawal`.
- **A6**: `settlement_days`는 그 달 daily summary row 수와 일치.

### B. 멱등성

- **B1**: 동일 yearMonth로 두 번째 실행해도 COMPLETED, row 수 변화 없음.

### C. 빈 입력

- **C1**: `yearMonth=2025-01`(데이터 없음)로 실행 시 COMPLETED, 0 row.

### D. Skip 정책

- **D1**: faultTolerant + skipLimit(10) + skip(IllegalStateException).

## Definition of Done

- A~D 모두 PASS, sprint-03-evaluation.md에 RESULT: PASS 기록.
