# bank-settlement-batch

Spring Batch 5로 만든 일일 정산 배치 시스템.

은행은 매일 새벽, 전날 발생한 모든 거래를 모아 계좌·회계 장부에 정리한다. 이 작업을 **일일 정산(daily settlement)** 이라고 부르며, 회계의 시작점이자 시스템 무결성의 마감 지점이다. 본 프로젝트는 그 정산 파이프라인을 4개의 Spring Batch Job으로 자동화한다.

기술 스택: **Spring Boot 3.5 · Spring Batch 5 · MySQL 8 · Java 17**.
개발 방법론: **Generator/Evaluator 하네스 패턴** (별도 평가자 에이전트가 매 sprint의 산출물을 회의적으로 검증).

## 어떻게 읽으면 좋은가

문서는 작은 것부터 큰 것까지 자연스럽게 이어진다. 위에서 아래로 한 번 통독하면 도메인 → 시스템 → 구현 → 방법론 순으로 그림이 잡힌다.

1. **[docs/01-domain.md](docs/01-domain.md)** — *왜 일일 정산이라는 작업이 존재하는가.* 분개·차변·대변·복식부기 같은 회계 용어, 거래 1건이 운영 시스템에서 회계 장부로 흘러가는 4단계.
2. **[docs/02-architecture.md](docs/02-architecture.md)** — *그 흐름을 어떤 컴포넌트로 구현했나.* 패키지·테이블·Job 구조와 5가지 핵심 설계 결정.
3. **[docs/03-batch-design.md](docs/03-batch-design.md)** — *왜 Spring Batch였고, 그 안의 어떤 패턴을 골랐나.* Airflow·cron 비교, chunk/tasklet/JobParameters 활용, 의도적으로 안 쓴 패턴.
4. **[docs/04-harness.md](docs/04-harness.md)** — *이 프로젝트는 어떻게 빌드됐나.* Claude Code 하네스 + 평가자 서브에이전트 패턴이 Sprint 01에서 11개 결함을 0으로 수렴시킨 사례.
5. **[docs/sprints/](docs/sprints/)** — Sprint 01~04 각각의 acceptance criteria + evaluator의 PASS/FAIL 리포트 페어. 위 4개 문서가 "이런 것이다"를 말한다면, 여기는 "이렇게 해서 이렇게 됐다"의 영수증.

## 빠른 시작

```bash
# 1) MySQL 컨테이너 기동 (포트 3307)
docker compose up -d

# 2) 시드 데이터 생성 (계좌 100, 거래 7,000)
./gradlew bootRun --args='--spring.profiles.active=seed'

# 3) 하루치 정산 파이프라인 실행
DATE=2026-05-05
./gradlew bootRun --args="--job=dailyTransactionAggregationJob --settlementDate=$DATE"
./gradlew bootRun --args="--job=journalEntryGenerationJob --entryDate=$DATE"

# 4) 한 달치 마감
./gradlew bootRun --args='--job=monthlyClosingJob --yearMonth=2026-05'

# 5) 결과 확인 (예시)
docker compose exec -T mysql mysql -uroot -psettlement1234 settlement_db \
    -e "SELECT account_number, total_deposit, total_withdrawal, net_amount
        FROM daily_transaction_summaries WHERE settlement_date='2026-05-05' LIMIT 5;"

# (선택) 운영 모드 — 매일 02:00, 매월 1일 03:00 cron 자동 트리거
./gradlew bootRun --args='--spring.profiles.active=scheduled'
```

전체 플로우 한 번에 돌리는 스크립트는 [`scripts/run-pipeline.sh`](scripts/run-pipeline.sh) 참고.

## 데이터 흐름 (검증 완료)

```
transactions (7,000건)
   ├── Job 1 ──> daily_transaction_summaries (700건 = 100 계좌 × 7일)
   │              └── Job 3 ──> monthly_account_summaries (100건)
   │
   └── Job 2 ──> journal_entries (14,000건 = 7,000 × debit/credit 2건)
                  invariant: SUM(debit) = SUM(credit) — 복식부기 보장
```

각 Job의 acceptance criteria와 실제 검증 결과는 [docs/sprints](docs/sprints/) 폴더에서 sprint별로 확인할 수 있다.

## Sprint 진행 현황

- [x] **Sprint 01** · DailyTransactionAggregationJob — 일별 거래 집계 (PASS, 1차 11결함 → 2차 PASS)
- [x] **Sprint 02** · JournalEntryGenerationJob — 분개 자동 생성 (PASS, 1차)
- [x] **Sprint 03** · MonthlyClosingJob — 월별 마감 (PASS, 1차)
- [x] **Sprint 04** · Scheduler + 재시작 개념 — 정기 트리거 (PASS)

## 디렉터리 구조

```
bank-settlement-batch/
├── .claude/
│   ├── settings.json              ── Hooks (Edit→compileJava, Stop→build)
│   └── agents/batch-evaluator.md  ── 회의적 평가자 서브에이전트 정의
├── docs/
│   ├── 01-domain.md ~ 04-harness.md
│   └── sprints/sprint-XX-*.md      ── Sprint별 contract & evaluation
├── docker-compose.yml              ── MySQL 8 (포트 3307)
├── scripts/run-pipeline.sh         ── 시드부터 월별 마감까지 일괄 실행
├── src/main/java/com/finance/settlement/
│   ├── batch/                     ── JobInvoker + 4개 Job 패키지
│   ├── config/DataSeeder.java
│   ├── domain/                    ── Account, Transaction, ...
│   └── repository/
└── src/main/resources/
    ├── application(.yml/-test/-seed/-scheduled).properties
    └── db/migration/V1__init_schema.sql
```

## 출처

- 일반적인 은행 정산 시스템 패턴을 참고했으며, 특정 회사·서비스를 대표하지 않는다.
- 개발 방법론은 Anthropic 블로그 *Harness design for long-running application development* (2026-03)을 참고했다.
