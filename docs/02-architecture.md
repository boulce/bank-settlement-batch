# 아키텍처

## 컴포넌트 다이어그램

```
                  ┌──────────────────────────────────────────────┐
                  │              Spring Boot Application          │
                  │                                                │
                  │  ┌────────────────────────────────────────┐   │
                  │  │ JobInvoker (ApplicationRunner)          │   │
                  │  │   --job=X --param=Y → JobLauncher       │   │
                  │  └─────────────────┬──────────────────────┘   │
                  │                    │                            │
                  │  ┌─────────────────▼──────────────────────┐   │
                  │  │ Spring Batch JobLauncher / JobRegistry  │   │
                  │  └─────────────────┬──────────────────────┘   │
                  │                    │                            │
                  │  ┌─────────────────▼──────────────────────┐   │
                  │  │ Jobs (Bean으로 등록된 모든 Job)          │   │
                  │  │   - dailyTransactionAggregationJob      │   │
                  │  │   - journalEntryGenerationJob (예정)    │   │
                  │  │   - monthlyClosingJob (예정)            │   │
                  │  └─────────────────┬──────────────────────┘   │
                  │                    │                            │
                  │  ┌─────────────────▼──────────────────────┐   │
                  │  │ Domain / Repository (Spring Data JPA)   │   │
                  │  └─────────────────┬──────────────────────┘   │
                  └────────────────────┼──────────────────────────┘
                                       │ JDBC
                  ┌────────────────────▼──────────────────────────┐
                  │   MySQL 8.0 (Docker, port 3307 → 3306)         │
                  │   ┌────────────┐  ┌──────────────────────┐    │
                  │   │ 비즈 테이블│  │ Spring Batch metadata │    │
                  │   │ accounts   │  │ BATCH_JOB_EXECUTION   │    │
                  │   │ transactions│ │ BATCH_STEP_EXECUTION  │    │
                  │   │ daily_..._  │ │ BATCH_JOB_INSTANCE    │    │
                  │   │ summaries  │  │ ...                   │    │
                  │   │ journal_   │  └──────────────────────┘    │
                  │   │ entries    │                              │
                  │   └────────────┘                              │
                  └────────────────────────────────────────────────┘
```

## 패키지 구조

```
com.finance.settlement
├── BankSettlementBatchApplication.java        — Spring Boot main
├── batch/
│   ├── JobInvoker.java                        — --job= 인자로 Job 트리거
│   ├── aggregation/                           — Job 1: 일일 거래 집계
│   │   ├── DailyTransactionAggregationJobConfig
│   │   ├── TransactionAggregationItemReader
│   │   ├── TransactionAggregationItemProcessor
│   │   ├── TransactionAggregationItemWriter
│   │   └── AggregateRow                       — JPQL 결과 DTO
│   ├── journal/                               — Job 2 (Sprint 02 예정)
│   └── closing/                               — Job 3 (Sprint 03 예정)
├── config/
│   └── DataSeeder.java                        — @Profile("seed") 더미데이터
├── domain/
│   ├── Account.java
│   ├── Transaction.java
│   ├── DailyTransactionSummary.java
│   └── JournalEntry.java
└── repository/
    ├── AccountRepository.java
    ├── TransactionRepository.java
    ├── DailyTransactionSummaryRepository.java
    └── JournalEntryRepository.java
```

## 데이터 흐름 (Job 1 기준)

```
   transactions (소스)
        │
        │  JPQL GROUP BY + 집계 함수 (SQL-level)
        ▼
   AggregateRow DTO 100개 (계좌별)        ← Reader (JpaPagingItemReader, page=1000)
        │
        │  1:1 변환 (Processor, stateless)
        ▼
   DailyTransactionSummary entity 100개
        │
        │  saveAll (chunk=1000)
        ▼
   daily_transaction_summaries (타깃)

   ※ Step 시작 전 cleanupStep(Tasklet)이 같은 settlementDate의
     기존 row를 일괄 삭제 → 재실행 멱등성 확보
```

## 핵심 설계 결정 5가지

### 1. SQL-level GROUP BY를 Reader 안에서

스트리밍 reader가 raw transaction을 1건씩 꺼내고 processor에서 누적하는 방식은 **chunk 경계에서 한 계좌가 잘리면 잘못된 결과**를 만든다. 더 결정적인 문제는, 같은 chunk에 같은 계좌의 거래 N건이 있으면 processor가 같은 logical entity를 N번 반환 → writer의 saveAll이 UNIQUE 제약 위반을 일으킨다.

**해결**: JPQL `GROUP BY` 구문을 reader 단계에서 사용. DB가 1 row/account로 미리 집계해서 던져준다. Processor는 stateless 1:1 변환만 한다.

### 2. cleanupStep + RunIdIncrementer로 멱등성

Spring Batch는 동일 JobParameters로 두 번 실행하면 `JobInstanceAlreadyCompleteException`을 던진다. 운영에서는 동일 일자로 재실행할 일이 빈번(실패 후 재시도, 데이터 보정 등).

**해결**:
- `RunIdIncrementer`로 매 실행마다 `run.id` 파라미터를 증가 → 새 JobInstance.
- 비즈 테이블 멱등성은 `cleanupStep` Tasklet이 보장 (동일 settlementDate row 일괄 삭제 후 aggregationStep 진행).

### 3. 잔액 컬럼 드랍

초기 `DailyTransactionSummary`는 `opening_balance`, `closing_balance`를 가졌다. 문제는 정확한 일별 잔액 추적은:
- 일별 잔액 스냅샷 테이블, 또는
- 매 거래마다 잔액을 갱신해 그 시점 잔액을 기록

이 둘 중 하나가 필요하며, 정산 집계 책임이 아니다. 두 컬럼은 의미가 모호하면서 정확성 검증이 까다롭기에 드랍.

### 4. 잡 트리거: spring.batch.job.enabled=false + 자체 JobInvoker

Spring Boot의 기본 `JobLauncherApplicationRunner`는 `--spring.batch.job.name`을 인자로 받는데, 미지정 시 등록된 모든 Job을 실행해버린다. 의도하지 않은 잡 실행 위험.

**해결**: 기본 launcher 비활성화 + 자체 `JobInvoker`(ApplicationRunner)가 `--job=X`만을 트리거. `--job` 미지정 시 idle (web 서버만 기동, scheduler 등 활용 가능).

### 5. JPA `validate` 모드 + 수동 마이그레이션

`spring.jpa.hibernate.ddl-auto=validate`로 운영 DB 스키마를 Hibernate가 자동 갱신하지 못하게 막는다. 스키마 변경은 `src/main/resources/db/migration/V1__init_schema.sql`이 docker 초기화 시 적용한다.

> 한계: 진정한 마이그레이션 도구(Flyway)는 도입하지 않았다. 본 시점은 V1 단일 스크립트만 사용. 본 프로젝트가 운영 단계로 갈 경우 Flyway/Liquibase 도입이 다음 단계.

## 환경별 프로필

| 프로필 | 용도 | DataSource |
|---|---|---|
| (default) | MySQL 연결 (localhost:3307) | `application.properties` |
| `seed` | DataSeeder 실행 후 즉시 종료 | default + DataSeeder bean 활성화 |
| `test` | 단위 테스트 (ContextLoad) | H2 in-memory (`MODE=MySQL`) |

```bash
# 시드 데이터 생성
./gradlew bootRun --args='--spring.profiles.active=seed'

# Job 1 실행 (기본 프로필)
./gradlew bootRun --args='--job=dailyTransactionAggregationJob --settlementDate=2026-05-05'

# 테스트
./gradlew test
```
