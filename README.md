# bank-settlement-batch

> 일반 목적 일일 정산 배치 시스템 (general-purpose daily settlement batch)
> Spring Boot 3.5 + Spring Batch 5 + MySQL 8 + Claude Code harness 기반

은행/금융 도메인의 일일 정산(daily settlement)을 Spring Batch로 자동화한 학습/포트폴리오 프로젝트입니다. 회계 도메인 지식이 없어도 이해할 수 있도록 문서를 단계적으로 두었으며, 개발 과정에서 Claude Code의 하네스 엔지니어링(자동 검증 훅 + 평가자 서브에이전트)을 활용했습니다.

## 빠른 시작

```bash
# 1) MySQL 컨테이너 기동 (포트 3307)
docker compose up -d

# 2) 시드 데이터 생성 (계좌 100, 일 1000 거래 x 7일)
./gradlew bootRun --args='--spring.profiles.active=seed'

# 3) Job 1 실행 (일별 거래 집계, 시드 범위 내 날짜)
./gradlew bootRun --args='--job=dailyTransactionAggregationJob --settlementDate=2026-05-05'

# 4) 결과 확인
docker compose exec -T mysql mysql -uroot -psettlement1234 settlement_db \
    -e "SELECT * FROM daily_transaction_summaries LIMIT 5;"

# 5) (선택) 분개 생성, 월별 마감
./gradlew bootRun --args='--job=journalEntryGenerationJob --entryDate=2026-05-05'
./gradlew bootRun --args='--job=monthlyClosingJob --yearMonth=2026-05'

# 6) (선택) 스케줄러 모드로 부팅 — 매일/매월 cron 자동 트리거
./gradlew bootRun --args='--spring.profiles.active=scheduled'
```

## 어디부터 읽어야 하나요?

회계/금융 도메인이 익숙하지 않다면 **반드시 [docs/01-domain.md](docs/01-domain.md)부터** 읽으세요. 분개·차변·대변·정산 같은 용어를 비전문가용으로 풀어 두었습니다.

| 순서 | 문서 | 내용 |
|---|---|---|
| 1 | [docs/01-domain.md](docs/01-domain.md) | 일일 정산이란 무엇이고 왜 배치인가 |
| 2 | [docs/02-architecture.md](docs/02-architecture.md) | 시스템 컴포넌트, 데이터 흐름, 핵심 설계 결정 5가지 |
| 3 | [docs/03-batch-design.md](docs/03-batch-design.md) | Spring Batch를 왜 어떻게 썼는지, 트레이드오프 |
| 4 | [docs/04-harness.md](docs/04-harness.md) | Claude Code harness — Hooks + 평가자 서브에이전트 |
| 5 | [docs/sprints/](docs/sprints/) | 각 Sprint의 acceptance criteria + 평가 리포트 |

## 기술 스택

- **Spring Boot 3.5.14** — 애플리케이션 컨테이너
- **Spring Batch 5.x** — 배치 프레임워크 (chunk + tasklet)
- **Spring Data JPA / Hibernate 6** — ORM
- **MySQL 8.0** (Docker) — 운영 DB
- **H2 (MODE=MySQL)** — 단위 테스트
- **Lombok** — 보일러플레이트 감축
- **Java 17** (toolchain), **Gradle 8** (Wrapper)

## 진행 상태

- [x] **Sprint 01: DailyTransactionAggregationJob** — 일별 거래 집계 (PASS)
- [x] **Sprint 02: JournalEntryGenerationJob** — 분개 자동 생성 (PASS)
- [x] **Sprint 03: MonthlyClosingJob** — 월별 마감 (PASS)
- [x] **Sprint 04: Scheduler** — 정기 자동 트리거 + 재시작 개념 정리 (PASS)

### 누적 데이터 흐름 (검증 완료)

```
transactions (7,000건)
   ├── Job 1 ──> daily_transaction_summaries (700건 = 100 계좌 × 7일)
   │              └── Job 3 ──> monthly_account_summaries (100건)
   │
   └── Job 2 ──> journal_entries (14,000건 = 7,000 × debit/credit 2건)
                  invariant: SUM(debit) = SUM(credit)
```

## 디렉터리 구조 (요약)

```
bank-settlement-batch/
├── .claude/
│   ├── settings.json              ── Hooks (compileJava/test/build 자동 실행)
│   └── agents/batch-evaluator.md  ── 회의적 평가자 서브에이전트
├── docs/
│   ├── 01-domain.md ~ 04-harness.md
│   └── sprints/sprint-XX-*.md      ── Sprint별 contract & 평가
├── docker-compose.yml             ── MySQL 8 (포트 3307)
├── src/main/java/com/finance/settlement/
│   ├── batch/                     ── JobInvoker + Job별 패키지
│   ├── config/DataSeeder.java
│   ├── domain/                    ── Account, Transaction, ...
│   └── repository/
└── src/main/resources/
    ├── application.properties
    ├── application-test.properties
    ├── application-seed.properties
    └── db/migration/V1__init_schema.sql
```

## 라이선스 / 출처

- 본 프로젝트는 학습 목적으로 작성되었으며, 특정 회사·서비스를 대표하지 않습니다.
- 시스템 설계 영감: 일반적인 은행 정산 시스템 패턴.
- 개발 워크플로우: Anthropic 블로그 *Harness design for long-running application development* (2026-03)을 참고해 평가자 서브에이전트 패턴을 도입.
