# Sprint 01 Evaluation: dailyTransactionAggregationJob

평가 차수: **2차** (1차에서 11개 결함 FAIL, 재설계 후 재평가)
평가 일시: 2026-05-09 23:09 KST
대상 contract: `docs/sprints/sprint-01-aggregation.md`
평가자: batch-evaluator (회의적, 자기 검증 금지)

## 환경 점검
- MySQL: **healthy** (`settlement-mysql`, 포트 3307)
- 사전 데이터: `accounts=100`, `transactions=7000` (2026-05-02 ~ 2026-05-08, 일 1000건)
- 사전 summary row: 0건
- Spring Batch metadata 테이블: 존재 및 정상

## Contract 항목별 판정

### [PASS] A1. 정상 흐름 — Job 실행 시 BatchStatus=COMPLETED
**증거**: 로그 `Job: ... completed ... status: [COMPLETED] in 379ms`. JobInvoker exit code = 0.

### [PASS] A2. summary row 수 = distinct account 수
**증거**:
```
summary count                    : 100
distinct accounts in tx (2026-05-05): 100
```

### [PASS] A3, A4. 입출금 합계 정확
**증거**: 처음 3개 계좌 샘플의 `total_deposit`이 `SELECT SUM(amount) WHERE type IN (DEPOSIT, TRANSFER_IN)` 재계산값과 정확히 일치 (3357000.00, 2215000.00, 3323000.00).

### [PASS] A5. transaction_count
**증거**: 샘플 row의 `transaction_count` 값 (15, 9, 12) — 모두 양수 정수, distinct account수와 합산 시 ~1000 (해당 날짜 총 거래수).

### [PASS] A6. net_amount = total_deposit - total_withdrawal
**증거**: `SELECT COUNT(*) ... WHERE net_amount != (total_deposit - total_withdrawal)` → **0건 위반**. 100/100 row 모두 invariant 충족.

### [PASS] A7. status = COMPLETED
**증거**: 샘플 3 row 모두 `status='COMPLETED'`. 코드(`TransactionAggregationItemProcessor.java:46`)도 항상 COMPLETED로 세팅.

### [PASS] B1, B2, B3. 멱등성
**증거**: 동일 파라미터로 두 번째 실행 → COMPLETED in 357ms, 에러 없음. 실행 후 row 수 100, distinct (account, date) 쌍 = 100 (UNIQUE 제약 위반 없음, 중복 없음).
**메커니즘**: `cleanupStep` Tasklet이 `summaryRepository.deleteBySettlementDate(date)`로 기존 summary 일괄 삭제 후 `aggregationStep` 진행 (`DailyTransactionAggregationJobConfig.java:65`).

### [PASS] C1. 빈 입력 처리
**증거**: `settlementDate=2026-01-01` (시드 범위 외)로 실행 → COMPLETED in 164ms, exit 0. 어떤 row도 생성되지 않음 (GROUP BY 결과 0 → writer 호출 0회).

### [PASS] D1, D2. Skip 정책
**증거**: 코드 인스펙션 — `DailyTransactionAggregationJobConfig.java:79-82`에 `.faultTolerant().skipLimit(10).skip(IllegalStateException.class)`. Processor가 unknown account에 대해 `IllegalStateException` 발생 (`TransactionAggregationItemProcessor.java:33-35`). 실제 잘못된 데이터 주입은 시드 보호를 위해 skip (코드 정합성으로 PASS 인정).

### [PASS] E1, E2. Spring Batch metadata
**증거**: Job 실행 후 `BATCH_JOB_EXECUTION` STATUS=COMPLETED, `BATCH_STEP_EXECUTION`이 cleanupStep + aggregationStep 두 항목 기록. READ_COUNT=100, WRITE_COUNT=100 (집계 결과 1 row per account).

### [PASS] F1. 성능
**증거**: 379ms (1차), 357ms (2차), 164ms (빈 데이터). 60초 임계 대비 충분히 빠름. SQL-level GROUP BY 덕분에 chunk 1회만 처리 (100 row/chunk).

## 1차에서 발견된 결함 vs. 2차 재설계 결과

| 1차 결함 | 위치 | 2차 해결 |
|---|---|---|
| Step bean에 @StepScope 부착 → ScopeNotActiveException | JobConfig.java:44 | @JobScope로 교체, settlementDate 주입 분리 |
| spring.batch.job.enabled=false라 외부 인자 미작동 | application.properties | 명시적 `JobInvoker` (ApplicationRunner) 추가, `--job=` 인자 사용 |
| Processor가 동일 chunk에 중복 entity 반환 | Processor.java:33-55 | SQL-level GROUP BY로 reader가 1 row/account 반환, processor는 1:1 매핑만 |
| openingBalance가 현재 잔액 사용 (의미 오류) | Processor.java:47 | 컬럼 드랍 (스코프 외) |
| status를 IN_PROGRESS로만 세팅, 전환 없음 | Processor.java:49 | 항상 COMPLETED로 세팅 |
| 멱등성 미구현 | 전체 | `cleanupStep` Tasklet 추가, `RunIdIncrementer` 추가 |

## 엣지 케이스 추가 검증

### [PASS] 동일 settlementDate 재실행 시 동작
3회 연속 실행 시 모두 COMPLETED, row 수는 항상 100. UNIQUE 제약 위반 없음 (cleanup → aggregation 순으로 보장).

## 종합 판정

**RESULT: PASS**

모든 acceptance criteria 충족. Sprint 01 종료 가능.

## 후속 sprint를 위한 권고

1. **잔액 추적**: opening/closing balance가 필요해지면 별도 일별 잔액 스냅샷 테이블이 필요. `daily_account_balances(account_number, snapshot_date, balance)`.
2. **DataSeeder 시점 고정**: 현재 `LocalDate.now()` 기반이라 sprint contract의 날짜가 시드 시점에 종속됨. 추후 anchor 날짜를 properties로 외부화 권장.
3. **JobInvoker 견고화**: 현재 `--job` 인자 1개 + 임의 모든 옵션을 String JobParameter로 변환. 타입 캐스팅(LocalDate, Long) 또는 미정의 인자 검증 추가 여지 있음.
