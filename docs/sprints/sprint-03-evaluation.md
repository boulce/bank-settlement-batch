# Sprint 03 Evaluation: monthlyClosingJob

평가 일시: 2026-05-09 23:21 KST
대상 contract: `docs/sprints/sprint-03-closing.md`
평가 차수: 1차 (PASS)

## 환경 점검
- DB 재초기화 후 시드 → 7일치 Job1 실행 → daily_transaction_summaries 700행 (100 계좌 × 7일)
- 사전 monthly_account_summaries: 0건

## 발견된 결함과 수정 (sprint 진행 중)
- **bean name 충돌**: Job2와 Job3 모두 `@Bean public ...jobListener()` 메소드명을 사용 → `BeanDefinitionOverrideException` 발생, 애플리케이션 부팅 실패. 각각 `journalJobListener`, `closingJobListener`로 리네임 후 해소. (Sprint 02 evaluator가 잡았어야 했지만 sprint를 단독 수행해 놓쳐, Job3 추가 시 노출됨.)

## Contract 항목별 판정

### [PASS] A1. Job 실행 → COMPLETED
**증거**: `Job: ... status: [COMPLETED] in 236ms`. JobInvoker exit=0.

### [PASS] A2. monthly summary count = distinct accounts
**증거**:
- monthly_account_summaries (2026-05) = **100**
- distinct account_number in daily_transaction_summaries (2026-05 범위) = **100**

### [PASS] A3. total_deposit = sum of daily total_deposit
**증거**: 5개 계좌 샘플 검증, 모두 정확 일치 (BigDecimal 단위까지):
```
110-123-000001: monthly=20,651,000.00  recomputed=20,651,000.00 ✓
110-123-000002: monthly=16,197,000.00  recomputed=16,197,000.00 ✓
110-123-000003: monthly=18,486,000.00  recomputed=18,486,000.00 ✓
110-123-000004: monthly=20,797,000.00  recomputed=20,797,000.00 ✓
110-123-000005: monthly=13,418,000.00  recomputed=13,418,000.00 ✓
```

### [PASS] A4. transaction_count = sum of daily counts
**증거**: 같은 5개 계좌의 monthly_tx (77, 62, 68, 86, 56)이 daily의 SUM(transaction_count)와 정확히 일치.

### [PASS] A5. net_amount = total_deposit - total_withdrawal
**증거**: `WHERE net_amount != (total_deposit - total_withdrawal)` → **0건 위반**.

### [PASS] A6. settlement_days
**증거**: MIN=7, MAX=7. 100 계좌 모두 7일 정산 (시드 데이터가 매일 모든 계좌에 거래를 분포시킨 결과 — 자연스러운 동작).

### [PASS] B1. 멱등성
**증거**: 같은 yearMonth로 두 번째 실행 → COMPLETED, row 수 변함 없이 100. UNIQUE 제약 위반 없음.
**메커니즘**: `closingCleanupStep` Tasklet이 `repository.deleteByYearMonthKey(ym)` 수행 후 closingStep 진행.

### [PASS] D1. Skip 정책
**증거**: 코드 인스펙션 — `MonthlyClosingJobConfig.java:80-82`에 `.faultTolerant().skipLimit(10).skip(IllegalStateException.class)` 설정.

## 종합 판정

**RESULT: PASS**

본 sprint에서 1개 결함(bean name 충돌)이 evaluator/contract 검증이 아닌 부팅 실패로 노출됐다. 후속 sprint에서는 evaluator의 점검 항목에 "bean name 중복 가능성"을 추가할 여지가 있다.

## Sprint 03까지의 누적 데이터 흐름 (검증 완료)

```
transactions (7,000건)
   ↓ Job 1
daily_transaction_summaries (700건 = 100 × 7일)
   ↓ Job 3
monthly_account_summaries (100건 = 1 row/account)

transactions (7,000건)
   ↓ Job 2 (같은 날짜에 대해)
journal_entries (14,000건 = 7,000 × 2 (debit+credit))
```
