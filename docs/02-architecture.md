## 2. 아키텍처

[01-domain.md](01-domain.md)에서 본 4단계 흐름(거래 발생 → 일일 집계 → 분개 → 월별 마감)을 코드와 테이블로 옮긴 결과를 정리한다.

이 문서는 Spring Batch를 처음 보는 독자를 가정한다. 익숙하다면 [§3 그래서 이 프로젝트의 모양은?](#3-그래서-이-프로젝트의-모양은) 부터 봐도 충분하다.

## 1. 큰 그림

이 프로젝트는 결국:

> **"4개의 배치 잡(Job)이 시간차를 두고 MySQL 안의 데이터를 다른 형태로 변환하는 시스템"**

다.

```
   transactions  ──Job 1──▶  daily_transaction_summaries
                    │
                    ├──Job 2──▶  journal_entries
                    │
                    └──Job 3──▶  monthly_account_summaries
```

각 Job은 SQL을 직접 짜서 변환할 수도 있지만, 본 프로젝트는 그 파이프라인을 **Spring Batch**라는 자바 프레임워크 위에 얹었다. Spring Batch가 무엇이고 왜 그것을 쓰면 좋은지가 다음 절의 주제.

## 2. Spring Batch 5분 입문

### 2.1 무엇을 하는 도구인가

대량의 데이터를 잘게 쪼개서 안전하게 처리하는 표준 패턴을 제공하는 자바 프레임워크. 직접 짜기 까다로운 다음을 알아서 처리해 준다:

- **메모리 안전 처리**: 천만 건짜리 테이블을 한 번에 메모리에 올리지 않고, 페이지 단위로 흘려보내기
- **트랜잭션 경계**: "한 번에 1000건씩 커밋"같은 chunk 단위 트랜잭션
- **재시작 / 중단 지점 추적**: 실패 시 메타데이터에 마지막 commit 지점이 남아 거기서부터 재개 가능
- **이력 관리**: 잡이 언제 시작/종료, 몇 건 처리, 몇 건 스킵했는지 자동 기록
- **재실행 보호**: 같은 파라미터로 같은 잡을 두 번 성공시키지 않음 (의도적 안전장치)

이걸 직접 구현하면 코드가 1000줄 넘게 들지만, Spring Batch는 builder API 몇 줄로 끝난다.

### 2.2 핵심 개념 — 위에서 아래로

```
┌─────────────────────────────────────────────────────────┐
│ Job  ─ 한 번에 실행되는 배치 작업의 단위                 │
│       (예: "2026-05-05일자 거래 집계")                 │
│                                                          │
│  ┌───────────────────────────────────────────────────┐  │
│  │ Step ─ Job을 구성하는 단계 (1개 이상)              │  │
│  │       (예: "정리 단계 → 집계 단계")               │  │
│  │                                                    │  │
│  │  Step의 두 가지 모양:                              │  │
│  │                                                    │  │
│  │  (A) Chunk-oriented:                              │  │
│  │      ┌──────┐   ┌────────────┐   ┌──────┐         │  │
│  │      │Reader│──▶│ Processor  │──▶│Writer│         │  │
│  │      └──────┘   └────────────┘   └──────┘         │  │
│  │      한 건씩    한 건씩          chunk(예:1000)   │  │
│  │      가져옴     변환/필터       마다 한 번 저장   │  │
│  │                                                    │  │
│  │  (B) Tasklet: 한 번에 끝나는 단발성 작업           │  │
│  │      (예: "settlement_date='2026-05-05' 행 일괄    │  │
│  │       삭제" 같은 단일 SQL)                         │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

용어를 한 번 더 정리:

| 용어 | 한 마디 | 본 프로젝트 예 |
|---|---|---|
| **Job** | 한 번 실행되는 배치 작업 | `dailyTransactionAggregationJob` |
| **Step** | Job 안의 한 단계 | `cleanupStep`, `aggregationStep` |
| **Chunk-oriented Step** | Reader→Processor→Writer 흐름. N건 모이면 한 번에 쓴다 | `aggregationStep` (chunk=1000) |
| **Tasklet** | 한 번에 끝나는 단발성 Step | `cleanupStep` (DELETE 한 번) |
| **Reader** | 데이터 한 건 가져오는 컴포넌트 | `JpaPagingItemReader` (페이지 단위 SELECT) |
| **Processor** | 한 건씩 변환/필터하는 컴포넌트 | `Transaction → DailyTransactionSummary` |
| **Writer** | chunk 단위로 저장하는 컴포넌트 | `repository.saveAll(...)` |
| **Chunk** | 트랜잭션 단위. "1000건 모이면 한 번 commit" | 본 프로젝트 모두 1000 |
| **JobInstance** | "같은 Job + 같은 파라미터"의 한 실행 인스턴스 | `dailyTransactionAggregationJob{settlementDate=2026-05-05}` |
| **JobParameters** | Job 실행 시 받는 외부 인자 | `settlementDate=2026-05-05` |
| **JobRepository** | 메타데이터 저장소 (`BATCH_JOB_EXECUTION` 등) | MySQL 같은 DB의 BATCH_* 테이블들 |
| **JobLauncher** | "잡 실행기" 인프라 | Spring Batch 기본 제공 |
| **JobRegistry** | "이름으로 잡 찾는 사전" 인프라 | Spring Batch 기본 제공 |
| **ApplicationRunner** | Spring Boot 부팅 시 자동 호출되는 콜백 | 우리가 만든 `JobInvoker` |
| **Bean** | Spring이 생성·관리하는 객체. `@Bean`/`@Component`로 표시 | 거의 모든 클래스 |

### 2.3 왜 JobInstance / JobParameters가 중요한가

Spring Batch의 가장 흔한 함정 둘이 여기서 나온다.

**같은 JobInstance를 두 번 성공시키지 않는다**:
- "같은 Job 이름 + 같은 JobParameters" 조합이 한 번 COMPLETED로 끝나면, 같은 조합으로 다시 실행하면 `JobInstanceAlreadyCompleteException`이 던져진다.
- 의도: "어제 이미 정산 끝낸 거 또 돌리는 사고"를 막는 안전장치.
- 부작용: 운영에서 "동일 일자 재실행"이 빈번한데(데이터 보정, 재시도) 매번 막힘.
- 해결 패턴 두 가지:
  - `RunIdIncrementer`: 매 실행마다 `run.id` 파라미터를 자동 증가 → 매번 새로운 JobInstance.
  - 멱등성 cleanup: 그래도 비즈 테이블에 부분 결과가 남아있으면 안 되므로, Job 시작에 "기존 결과 일괄 삭제" Tasklet을 둔다.

본 프로젝트는 두 가지를 다 쓴다.

## 3. 그래서 이 프로젝트의 모양은?

위 개념을 사용해 다시 그리면:

```
   [터미널]
    │  ./gradlew bootRun --args='--job=dailyTransactionAggregationJob --settlementDate=2026-05-05'
    ▼
   ┌───────────────────────────────────────────────────────┐
   │  Spring Boot Application                              │
   │                                                       │
   │  JobInvoker  (ApplicationRunner — 부팅시 자동 호출)   │
   │     │  --job= 인자 파싱                              │
   │     ▼                                                 │
   │  JobRegistry (이름→Job 사전)  ─────┐                 │
   │     │                              │                 │
   │     ▼                              ▼                 │
   │  JobLauncher.run(job, params) ── 4개의 Job Bean       │
   │                                   ├─ daily~Aggregation│
   │                                   ├─ journalEntry~   │
   │                                   ├─ monthlyClosing  │
   │                                   └─ (etc)           │
   │     │                                                 │
   │     ▼ Step별로 순차 실행                              │
   │  Reader → Processor → Writer (chunk-oriented)         │
   │  또는 Tasklet                                         │
   │     │                                                 │
   │     ▼ 비즈 테이블 read/write                          │
   │  Spring Data JPA (Hibernate)                          │
   └─────────┬─────────────────────────────────────────────┘
             │  JDBC
             ▼
   ┌───────────────────────────────────────────────────────┐
   │  MySQL 8.0 (Docker, host port 3307)                   │
   │                                                       │
   │  비즈 테이블 (도메인 데이터)                           │
   │   accounts · transactions · daily_transaction_~       │
   │   journal_entries · monthly_account_summaries         │
   │                                                       │
   │  Spring Batch 메타데이터 (자동 생성)                   │
   │   BATCH_JOB_INSTANCE · BATCH_JOB_EXECUTION            │
   │   BATCH_STEP_EXECUTION · BATCH_STEP_EXECUTION_CONTEXT │
   │   ...                                                 │
   └───────────────────────────────────────────────────────┘
```

흐름을 풀어 쓰면:

1. 터미널에서 `--job=...` 인자로 Spring Boot 부팅
2. 부팅 끝나면 Spring Boot가 모든 `ApplicationRunner` 빈을 호출 → 우리의 `JobInvoker.run()` 실행
3. `JobInvoker`가 `--job=` 값을 읽어 `JobRegistry`에서 해당 Job 빈을 찾음
4. `JobLauncher.run(job, params)` 호출 → 그 Job의 Step들을 순서대로 실행
5. Step이 chunk-oriented면 Reader→Processor→Writer를 1000건씩 commit하며 반복, Tasklet이면 단일 작업 한 번 실행
6. 비즈 결과는 `daily_transaction_summaries` 등에, 실행 이력은 `BATCH_JOB_EXECUTION` 등에 자동 기록
7. Job 끝나면 `JobInvoker`가 `System.exit()`으로 JVM 종료

## 4. 패키지 구조

```
com.finance.settlement
├── BankSettlementBatchApplication.java        ── Spring Boot 진입점 (@SpringBootApplication, @EnableScheduling)
├── batch/                                     ── 모든 배치 코드
│   ├── JobInvoker.java                        ── --job= 인자로 Job 트리거하는 ApplicationRunner
│   ├── SettlementScheduler.java               ── @Profile("scheduled")일 때만 활성화되는 cron 트리거
│   │
│   ├── aggregation/                           ── Job 1: 일일 거래 집계
│   │   ├── DailyTransactionAggregationJobConfig  · Job + Step 빈 정의 (전체 와이어링)
│   │   ├── TransactionAggregationItemReader      · Reader 팩토리. JPQL GROUP BY 페이징 쿼리
│   │   ├── TransactionAggregationItemProcessor   · 1:1 변환 (AggregateRow → DailyTransactionSummary)
│   │   ├── TransactionAggregationItemWriter      · saveAll로 DB에 저장
│   │   └── AggregateRow                          · JPQL 결과를 받는 DTO (계좌별 합계)
│   │
│   ├── journal/                               ── Job 2: 분개 생성
│   │   ├── JournalEntryGenerationJobConfig
│   │   ├── JournalEntryItemReader                · Transaction 한 건씩 읽기
│   │   ├── JournalEntryItemProcessor             · 1:N 변환 (Transaction 1건 → JournalEntry 2건)
│   │   ├── JournalEntryItemWriter                · 평탄화 후 saveAll
│   │   └── AccountCodeMapping                    · TransactionType → 차변/대변 계정코드 매핑
│   │
│   └── closing/                               ── Job 3: 월별 마감
│       ├── MonthlyClosingJobConfig
│       ├── MonthlyClosingItemReader              · daily_transaction_summaries를 월 단위로 GROUP BY
│       ├── MonthlyClosingItemProcessor
│       ├── MonthlyClosingItemWriter
│       └── MonthlyAggregateRow
│
├── config/
│   └── DataSeeder.java                        ── @Profile("seed") — 더미 데이터 5,000계좌 × 35,000거래 생성
│
├── domain/                                    ── JPA 엔티티 (= 비즈 테이블 매핑 객체)
│   ├── Account.java
│   ├── Transaction.java
│   ├── DailyTransactionSummary.java
│   ├── JournalEntry.java
│   └── MonthlyAccountSummary.java
│
└── repository/                                ── Spring Data JPA repository 인터페이스
    ├── AccountRepository.java
    ├── TransactionRepository.java
    ├── DailyTransactionSummaryRepository.java
    ├── JournalEntryRepository.java
    └── MonthlyAccountSummaryRepository.java
```

## 5. Job 1의 데이터 흐름 (구체 사례)

Job 1을 예로 들어 위 추상 모델이 실제로 어떻게 실행되는지:

```
[Step 1: cleanupStep — Tasklet]
   summaryRepository.deleteBySettlementDate('2026-05-05')   ← 한 번의 SQL DELETE
   → 기존에 같은 날짜로 만들어졌던 summary 일괄 삭제

[Step 2: aggregationStep — Chunk-oriented]
   transactions 테이블
        │
        │  Reader: JpaPagingItemReader가 아래 JPQL을 1000건씩 페이징
        │
        │    SELECT new AggregateRow(
        │      t.accountNumber,
        │      SUM(case when type='DEPOSIT' or 'TRANSFER_IN' then amount else 0 end),
        │      SUM(case when type='WITHDRAWAL' or ... then amount else 0 end),
        │      COUNT(t)
        │    )
        │    FROM Transaction t
        │    WHERE t.transactionDate = :date AND t.status = COMPLETED
        │    GROUP BY t.accountNumber
        │
        ▼
   AggregateRow ≈3,160개 (그 날 거래가 있었던 계좌별 1행 — 시드는 5000 계좌 중 랜덤 분배)
        │
        │  Processor: 1:1 변환 (stateless)
        │    AggregateRow → DailyTransactionSummary 엔티티
        │
        ▼
   DailyTransactionSummary 엔티티 ≈3,160개
        │
        │  Writer: chunk 단위로 saveAll → INSERT
        │  (chunk size=1000이므로 3160 → 1000+1000+1000+160으로 4번에 나눠 commit)
        │
        ▼
   daily_transaction_summaries 테이블에 ≈3,160행 INSERT
```

> **chunk size 보충**: chunk size는 "최대 N건까지 메모리에 모았다가 한 번에 Writer로 보내는 버퍼 크기"다. 두 조건 중 먼저 만족되는 쪽에서 flush 트리거: ① 버퍼가 N건 가득 참, 또는 ② Reader가 더 줄 게 없음을 알림(null 리턴). 마지막 chunk는 거의 항상 N개 미만.
>
> 본 프로젝트는 시드를 5000계좌 × 5000거래/일 규모로 설계해서 chunk가 실제로 여러 번 fired되는 것을 metadata에서 직접 확인 가능: `BATCH_STEP_EXECUTION.commit_count` 가 Job 1 aggregationStep은 4, Job 2 journalGenerationStep과 Job 3 closingStep은 6 (= 5000/1000 chunk + 마지막 빈 chunk 1번).

핵심: **집계는 Reader 단계에서 SQL이 처리**한다. Processor는 변환만, Writer는 저장만. 각자 책임이 1줄로 떨어진다.

## 6. 핵심 설계 결정 5가지

각 결정의 "왜 그게 문제인지"부터 풀어 쓰는 형식.

### 6.1 Reader에서 SQL-level GROUP BY로 집계

**처음 떠올리는 방식**: Reader가 transaction을 한 건씩 꺼내고, Processor가 `Map<account, summary>`에 누적하다가 마지막에 모두 emit.

**이게 왜 망가지나**:
- Spring Batch는 chunk(예: 1000) 단위로 트랜잭션 commit. processor가 chunk 안에서 같은 계좌의 거래 N건을 처리하면 같은 logical 엔티티를 N번 emit하게 된다.
- Writer는 N개의 transient(=DB에 아직 없는) 엔티티에 대해 INSERT를 N번 시도 → `daily_transaction_summaries`의 UNIQUE(account_number, settlement_date) 제약 위반.
- 게다가 chunk 경계에서 한 계좌의 거래가 잘리면 부분 합계가 두 번 나오는 등 정확성도 깨진다.

**해결**: 집계를 **DB 쪽에서 GROUP BY로 끝낸 다음** Reader가 1행/계좌만 가져온다. Processor는 단순 1:1 매핑이라 chunk 경계 문제가 원천 차단.

### 6.2 cleanupStep + RunIdIncrementer로 멱등성 확보

**왜 필요한가**: §2.3에서 본 대로, 같은 JobParameters로 같은 Job을 두 번 성공시키려 하면 `JobInstanceAlreadyCompleteException`이 던져진다. 운영에서는 동일 일자 재실행이 빈번한데(보정, 재시도) 매번 막힘.

**두 가지를 같이 쓴다**:
- `RunIdIncrementer`: Job 정의에 붙이면 매 실행마다 `run.id` 파라미터를 1씩 증가시킨다 → 매번 새로운 JobInstance라 예외 회피.
- `cleanupStep` Tasklet: 그래도 비즈 테이블에 이전 부분 결과가 남으면 UNIQUE 제약 위반이나 결과 오염 위험. cleanupStep이 같은 settlementDate row를 일괄 삭제 후 aggregationStep을 진행해 이를 방지.

### 6.3 잔액(opening/closing balance) 컬럼 드랍

**처음 설계 시 의도**: `DailyTransactionSummary`에 `opening_balance`, `closing_balance`도 함께 기록.

**왜 안 하는 게 나은가**:
- 정확한 일별 잔액은 둘 중 하나가 필요:
  - 별도 일별 잔액 스냅샷 테이블, 또는
  - 매 거래마다 잔액을 갱신해 그 시점 잔액을 transaction 테이블에 같이 기록
- 둘 다 정산 집계 책임 영역이 아니다. 다른 시스템(코어뱅킹)의 일.
- 두 컬럼은 의미가 모호한 채로 들어가면 검증도 까다로움.

**해결**: 컬럼을 드랍. 의미 있는 잔액 추적이 필요해지면 별도 모듈로 추가.

### 6.4 잡 트리거: 자체 JobInvoker

**Spring Boot 기본 동작**: `spring.batch.job.enabled=true`일 때 `JobLauncherApplicationRunner`가 자동으로 등록되어, `--spring.batch.job.name=X` 인자가 있으면 그 Job을 실행한다. 인자가 없으면 **등록된 모든 Job을 실행**(!)해버린다.

**문제**: 의도하지 않게 모든 Job이 다 도는 사고 위험. 특히 seed 모드 등 다른 목적으로 부팅할 때 위험.

**해결**: 기본 launcher 비활성화(`enabled=false`) + 우리가 만든 `JobInvoker` ApplicationRunner가 `--job=X` 인자를 받아 그 잡만 실행. `--job` 인자 없으면 아무 일도 안 함 (web 서버만 떠 있는 상태로 idle, scheduler 등이 트리거할 수 있음).

### 6.5 JPA `validate` 모드 + 수동 마이그레이션

**Spring Data JPA의 ddl-auto 옵션**:
- `create`: 부팅 시마다 테이블 drop&create — 데이터 다 날아감
- `update`: 엔티티 변경에 맞춰 ALTER TABLE 자동 발행 — 운영에서 위험
- `validate`: 엔티티와 실제 스키마가 일치하는지 검증만, 자동 변경 X
- `none`: 아무 것도 안 함

**선택**: `validate`. 운영 안전성 ↑, 단 스키마 변경은 사람이 명시적으로 해야 함.

**현재 방식**: `src/main/resources/db/migration/V1__init_schema.sql`이 docker-compose의 MySQL 컨테이너 초기 부팅 시(`docker-entrypoint-initdb.d`) 한 번 적용됨. 이후 변경은 docker volume drop + 재부팅 또는 직접 ALTER 필요.

**한계**: V1 단일 스크립트만 사용. 진정한 마이그레이션 도구(Flyway/Liquibase)는 도입 안 함. 운영 단계로 가면 도입이 다음 자연 step.

## 7. 환경별 프로필

Spring Boot의 "profile"은 부팅 시 활성화되는 설정 묶음. `--spring.profiles.active=NAME`으로 지정.

| 프로필 | 용도 | 어떤 빈이 활성? |
|---|---|---|
| (default) | MySQL 연결, 기본 운영 모드 | JobInvoker (--job 대기) |
| `seed` | 더미 데이터 생성 후 즉시 종료 | DataSeeder (`@Profile("seed")`) |
| `scheduled` | cron 자동 트리거 모드 | SettlementScheduler (`@Profile("scheduled")`) |
| `test` | 단위/통합 테스트 (H2 in-memory) | 메인 빈만, MySQL 연결 X |

```bash
# 시드 데이터 생성
./gradlew bootRun --args='--spring.profiles.active=seed'

# Job 1 단발 실행 (default 프로필)
./gradlew bootRun --args='--job=dailyTransactionAggregationJob --settlementDate=2026-05-05'

# 운영 모드 — 매일 02:00, 매월 1일 03:00 자동 트리거
./gradlew bootRun --args='--spring.profiles.active=scheduled'

# 테스트
./gradlew test
```

---

이 컴포넌트들을 묶고 있는 Spring Batch 자체에 대한 결정과 트레이드오프(왜 Spring Batch였고, 어떤 패턴을 골랐고, 어떤 패턴은 의도적으로 안 썼나)는 다음 문서에서 다룬다 → [03-batch-design.md](03-batch-design.md).
