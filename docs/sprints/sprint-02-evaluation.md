# Sprint 02 Evaluation: journalEntryGenerationJob

평가 일시: 2026-05-09 23:16 KST
대상 contract: `docs/sprints/sprint-02-journal.md`
평가 차수: **1차** (Sprint 01 패턴을 그대로 적용해 첫 평가에서 PASS)

## 환경 점검
- MySQL: healthy, 사전 데이터 transactions=7000
- 사전 journal_entries row: 0건
- Spring Batch metadata: 정상

## Contract 항목별 판정

### [PASS] A1. Job 실행 → COMPLETED
**증거**: `Job: [...] status: [COMPLETED] in 932ms`. JobInvoker exit=0.

### [PASS] A2. journal_entries count = 2 × tx count
**증거**:
- `SELECT COUNT(*) FROM journal_entries WHERE entry_date='2026-05-05'` → **2000**
- `SELECT COUNT(*) FROM transactions WHERE transaction_date='2026-05-05' AND status='COMPLETED'` → **1000**
- 2000 = 2 × 1000 ✓

### [PASS] A3. Per-transaction invariant (차변 = 대변)
**증거**: `SELECT COUNT(*) FROM (... GROUP BY transaction_id HAVING SUM(...) != 0)` → **0건 위반**.
1000개 transaction 모두 차변 합계 = 대변 합계.

### [PASS] A4. Aggregate invariant (전체 차변 = 전체 대변)
**증거**:
```
total_debit  = 494,063,000.00
total_credit = 494,063,000.00
```
정확히 일치.

### [PASS] A5. 거래타입 → 계정 매핑 정확
**증거**: DEPOSIT 거래 샘플 2건의 분개 4행이 매핑표와 일치:
```
DEBIT  101 현금     726000.00
CREDIT 201 고객예금 726000.00
DEBIT  101 현금     452000.00
CREDIT 201 고객예금 452000.00
```
HEX 검증으로 한글 UTF-8 정상 저장 확인 (`고객예금` = `EAB3A0EAB09DEC9888EAB888`).
코드 매핑은 `AccountCodeMapping.java:13-19`에 정적으로 정의.

### [PASS] B1. 멱등성
**증거**: 동일 `entryDate=2026-05-05`로 두 번째 실행 → COMPLETED. 실행 후 row count 여전히 2000건 (중복 없음).
**메커니즘**: `journalCleanupStep`이 `journalEntryRepository.deleteByEntryDate(date)`로 기존 row 일괄 삭제 후 generation 진행 (`JournalEntryGenerationJobConfig.java:54-65`).

### [PASS] C1. 빈 입력 처리
**증거**: `entryDate=2026-01-01`로 실행 → COMPLETED in 162ms. row 생성 0건 (Reader가 빈 결과를 반환하면 chunk loop 미발동).

### [PASS] D1. Skip 정책
**증거**: 코드 인스펙션 — `JournalEntryGenerationJobConfig.java:78-80`에 `.faultTolerant().skipLimit(10).skip(IllegalStateException.class)`.
Processor가 transaction_type null인 경우 `IllegalStateException` 발생 (`JournalEntryItemProcessor.java:23`).

### [PASS] E1. Spring Batch metadata
**증거**: Job 실행 후 `BATCH_JOB_EXECUTION` STATUS=COMPLETED 기록. STEP_EXECUTION에 journalCleanupStep + journalGenerationStep 두 행 모두 기록 (read=1000, write=1000).
주의: `write=1000`은 chunk item 수(`List<JournalEntry>` 1000개)이며, 실제 journal_entries row 수는 2000개.

## 엣지 케이스 추가 검증

### [PASS] 한글 UTF-8 저장
account_name 컬럼에 저장된 한글이 손상 없이 유지됨. HEX 검증으로 UTF-8 시퀀스 정확.

### [PASS] BigDecimal scale
journal_entries.amount는 DECIMAL(20,2). saveAll 후 amount가 source transaction의 amount와 정확히 동일.

## 종합 판정

**RESULT: PASS**

Sprint 01에서 학습한 패턴(SQL-driven reader, stateless processor, cleanup tasklet, RunIdIncrementer)을 그대로 적용한 결과 1차 평가에서 모든 항목 PASS. Generator/Evaluator 분리 패턴의 학습 효과가 확인됨.

## 후속 sprint 권고

1. **계정 매핑 외부화**: `AccountCodeMapping`을 `account_code_rules` 테이블로 이전 가능. 회계 정책 변경 시 코드 배포 없이 룰만 갱신.
2. **Sprint 03 (Monthly Closing)**: 일일 summary를 월별로 다시 합산. 본 Job 결과(`journal_entries`)가 월별 분개의 입력이 될 수도 있음.
