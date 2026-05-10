# Sprint 02 — Journal Entry Generation Job

## 배경

Sprint 01이 끝나면 `daily_transaction_summaries`에 계좌별 일일 집계가 들어 있다. 하지만 회계 장부는 별도다. 이 Job은 거래 1건마다 **회계 분개(차변 1행 + 대변 1행)**를 만들어 `journal_entries`에 기록한다.

회계 도메인 설명은 `docs/01-domain.md` 참고. 핵심: **모든 거래는 차변·대변 양쪽에 같은 금액으로 기록되어, 차변 합계 = 대변 합계라는 invariant이 항상 성립해야 한다 (복식부기).**

## 거래 타입별 계정 매핑

은행 회계 관점에서 각 거래가 자산/부채/수익에 어떤 변동을 일으키는지 함께 표시한다 (차변·대변 규칙 자체는 [01-domain.md](../01-domain.md#차변대변-규칙--5가지-계정-종류-전체) 참고).

| Transaction.TransactionType | 차변 (DEBIT) | 대변 (CREDIT) |
|---|---|---|
| DEPOSIT (입금) | 101 현금 (자산↑) | 201 고객예금 (부채↑) |
| WITHDRAWAL (출금) | 201 고객예금 (부채↓) | 101 현금 (자산↓) |
| TRANSFER_IN (타행 이체 입금) | 501 이체대기 (자산↑) | 201 고객예금 (부채↑) |
| TRANSFER_OUT (타행 이체 출금) | 201 고객예금 (부채↓) | 501 이체대기 (자산↓) |
| FEE (수수료) | 201 고객예금 (부채↓) | 401 수수료수익 (수익↑) |

> ※ 회계 등식 `자산 = 부채 + 자본` 관점:
> - DEPOSIT/TRANSFER_IN: 자산↑ + 부채↑ — 양변이 같이 증가
> - WITHDRAWAL/TRANSFER_OUT: 자산↓ + 부채↓ — 양변이 같이 감소
> - FEE: 부채↓ + 수익↑(=자본↑) — 자산은 그대로, 부채 감소만큼 자본 증가
>
> 모든 케이스에서 등식이 유지된다 (복식부기의 핵심).

### 단순화 사항

**이체대기(501)** 계정은 입출금 양방향에서 같은 코드를 쓴다. 실무에서는 보통 두 계정으로 분리:
- "타행 미수금" (자산) — 받을 돈
- "타행 미지급금" (부채) — 보낼 돈

본 프로젝트는 학습 단순화를 위해 단일 임시 계정으로 묶고, 매핑 일관성을 위해 **자산으로 분류**한다 (그래서 TRANSFER_OUT의 대변에서 자산↓로 표기). 매핑 룰 외부화 + 계정 분리는 후속 sprint 후보.

## 입출력

- **JobParameter**: `entryDate` (YYYY-MM-DD)
- **소스 테이블**: `transactions` 테이블, `transaction_date = entryDate AND status='COMPLETED'`
- **타깃 테이블**: `journal_entries`, 거래 1건당 2 row

## Acceptance Criteria

### A. 정상 흐름

- **A1**: `entryDate=2026-05-05`로 Job 실행 시 BatchStatus=COMPLETED.
- **A2**: 생성된 journal_entry 수 = 해당 날짜의 COMPLETED transaction 수 × 2.
  - 검증: `SELECT COUNT(*) FROM journal_entries WHERE entry_date='2026-05-05'` == `2 × SELECT COUNT(*) FROM transactions WHERE transaction_date='2026-05-05' AND status='COMPLETED'`.
- **A3 (per-tx invariant)**: 각 transaction_id에 대해, 차변 합계 = 대변 합계.
  - 검증: `SELECT transaction_id, SUM(CASE entry_type='DEBIT' THEN amount ELSE -amount END) AS diff FROM journal_entries WHERE entry_date='2026-05-05' GROUP BY transaction_id` → 모든 row의 diff = 0.
- **A4 (aggregate invariant)**: 그 날 차변 총액 = 대변 총액.
  - 검증: `SELECT SUM(amount) WHERE entry_type='DEBIT' AND entry_date=...` == `SELECT SUM(amount) WHERE entry_type='CREDIT' AND entry_date=...`.
- **A5 (mapping)**: 임의 transaction의 차변/대변 entry가 위 매핑표와 정확히 일치.
  - 검증: 샘플 추출 후 transaction_type 별 entry 정합성.

### B. 멱등성

- **B1**: 동일 entryDate로 두 번째 실행해도 COMPLETED, journal_entry 총 row 수는 변화 없음 (cleanup 후 재생성).

### C. 빈 입력

- **C1**: `entryDate=2026-01-01` (해당 날 거래 0건)로 실행 시 COMPLETED, 0 row 생성.

### D. Skip 정책

- **D1**: faultTolerant 설정 — IllegalStateException(예: 알 수 없는 transaction type) skip, 최대 10건.

### E. Spring Batch metadata

- **E1**: `BATCH_JOB_EXECUTION` 기록, status=COMPLETED.

## Definition of Done

- A1~D1 모두 PASS.
- Sprint 02 evaluation report (`sprint-02-evaluation.md`)가 RESULT: PASS.
