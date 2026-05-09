# Sprint 01 — Daily Transaction Aggregation Job

## 배경 (회계 도메인 모르는 사람을 위한 설명)

은행에서는 하루 동안 발생한 모든 거래를 다음날 새벽에 **계좌 단위로 집계**합니다. 이를 "일일 정산(daily settlement)"이라고 부르며, 회계 장부의 출발점이 됩니다.

이 Job(Job 1)이 하는 일:

> 어떤 날짜를 입력받아, 그 날 발생한 모든 거래를 계좌별로 합산해서 `daily_transaction_summaries` 테이블에 1행씩 저장한다.

예: 2026-05-05에 110-123-000001 계좌에서 입금 5건(총 50만원), 출금 3건(총 20만원)이 있었다면 → 그 날 그 계좌의 summary 1행: `total_deposit=500000, total_withdrawal=200000, transaction_count=8, net_amount=300000`.

이게 왜 배치인가? 거래 1건마다 실시간으로 집계하면 시스템 부하가 크고, 회계상 "당일 마감" 개념이 필요해서 새벽에 한꺼번에 처리하는 것이 표준 패턴입니다.

## 입력

- **JobParameter**: `settlementDate` (YYYY-MM-DD). 누락 시 어제 날짜.
- **소스 테이블**: `transactions` 테이블, `transaction_date = settlementDate AND status='COMPLETED'`인 row.

## 출력

- **타깃 테이블**: `daily_transaction_summaries`, settlementDate에 거래가 있었던 계좌 수만큼 row 생성.

## Acceptance Criteria

각 항목은 evaluator가 실제 DB로 검증한다.

### A. 정상 흐름

- **A1**: `settlementDate=2026-05-05`로 Job 실행 시 BatchStatus=COMPLETED.
- **A2**: 생성된 summary row 수 = 그 날 거래가 있었던 distinct account 수.
  - 검증: `SELECT COUNT(*) FROM daily_transaction_summaries WHERE settlement_date='2026-05-05'` == `SELECT COUNT(DISTINCT account_number) FROM transactions WHERE transaction_date='2026-05-05' AND status='COMPLETED'`.
- **A3**: 임의 계좌의 `total_deposit`은 그 날 그 계좌의 `DEPOSIT + TRANSFER_IN` 거래 합계와 정확히 일치.
- **A4**: 임의 계좌의 `total_withdrawal`은 그 날 그 계좌의 `WITHDRAWAL + TRANSFER_OUT` 거래 합계와 정확히 일치 (FEE는 일단 출금으로 취급, 검증에서 정확한 분류 확인).
- **A5**: `transaction_count`는 해당 (account, date)의 COMPLETED 거래 수.
- **A6**: `net_amount = total_deposit - total_withdrawal` (BigDecimal 정확 일치).
- **A7**: 모든 summary row의 `status`는 정상 종료 시 `COMPLETED`.

> 주의: 초기 설계의 `opening_balance` / `closing_balance` 컬럼은 본 sprint에서 의도적으로 드랍했다. 정확한 일별 잔액 추적은 별도의 일별 잔액 스냅샷 테이블이 필요한 영역이며, 본 프로젝트의 정산 집계 책임 범위 밖이다. 이 결정은 `docs/02-architecture.md`에서 상세히 다룬다.

### B. 멱등성 (가장 중요)

- **B1**: 동일 settlementDate로 Job을 두 번째 실행해도 에러 없이 COMPLETED.
- **B2**: 두 번 실행 후에도 summary row 수는 동일(중복 없음, UNIQUE 제약 위반 없음).
- **B3**: 두 번째 실행 결과 row의 모든 컬럼이 첫 실행과 동일.

### C. 빈 입력 처리

- **C1**: 거래가 없는 날짜(예: `2026-01-01`)로 실행 시 에러 없이 COMPLETED, summary 0행 생성.

### D. Skip 정책

- **D1**: faultTolerant 설정 — `IllegalStateException` 발생 시 스킵, 최대 10건까지.
- **D2**: 의도적으로 존재하지 않는 account_number를 가진 transaction을 1건 삽입한 뒤 Job 실행 시, skipCount=1로 COMPLETED, 나머지 정상 집계됨.

### E. Spring Batch metadata

- **E1**: `BATCH_JOB_EXECUTION` 테이블에 실행 이력 기록됨, status=COMPLETED.
- **E2**: `BATCH_STEP_EXECUTION`의 `READ_COUNT`, `WRITE_COUNT`, `COMMIT_COUNT`가 합리적인 값.

### F. 성능 (참고 지표)

- **F1**: 7000건 거래 처리 시 60초 이내 종료 (개발 환경 MySQL Docker 기준).

## Definition of Done

- 위 A~E가 모두 PASS.
- F는 참고 지표 (FAIL이어도 전체 PASS는 가능, 단 코멘트 남길 것).
- evaluator의 종합 판정: `RESULT: PASS`.

## 비고

- 현 구현은 sprint 시작 전에 작성되었으므로 evaluator의 첫 패스에서 FAIL이 예상된다 (특히 B 멱등성 / openingBalance 의미론).
- FAIL 항목별로 수정 후 재평가, 모두 PASS 될 때까지 반복.
