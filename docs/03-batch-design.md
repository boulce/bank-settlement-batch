## 3. Spring Batch 설계 결정

[02-architecture.md](02-architecture.md)는 *무엇을* 만들었는지 — 어휘 정의와 컴포넌트 구조 — 를 다뤘다. 본 문서는 *왜 그렇게 결정했는지*에 집중한다:

- 왜 Spring Batch였나 (Airflow / cron + 일반 코드 비교)
- 어떤 패턴을 골랐고, 그 결정으로 무엇을 잃었나
- 의도적으로 *안 쓴* 패턴은 무엇이고, 왜 안 썼나
- Spring Batch가 자동으로 남기는 메타데이터를 어떻게 활용하나

## 1. 왜 Spring Batch였나

### 1.1 정산 도메인이 요구하는 것

배치 도구를 고르기 전에, 정산 도메인이 무엇을 강하게 요구하는지부터 정리:

| 요구 | 의미 |
|---|---|
| **무결성** | 한 번 commit된 일별 정산 결과는 잘못되면 안 됨. 부분 실패 시 깔끔한 롤백 필요 |
| **재처리 가능** | 실패하면 같은 날 다시 돌릴 수 있어야 함 (운영에서 흔한 일). 두 번 돌려도 결과 동일 |
| **메모리 안전** | 수십만~수천만 거래를 한 번에 메모리에 올리면 안 됨. 페이지 단위로 흘려보내기 |
| **이력 추적** | 언제 시작/종료, 몇 건 처리, 성공/실패 — 회계 감사 시 필요 |

이 4가지를 직접 짜면 1000줄 넘는 보일러플레이트가 된다. "이걸 표준으로 제공하는 도구가 무엇인가"가 선택의 핵심.

### 1.2 후보 셋

#### (A) cron + 자체 자바 스크립트

**무엇**: 유닉스 cron(정해진 시각에 명령을 자동 실행하는 스케줄러)이 매일 02:00에 `java -jar settlement.jar`를 호출. 자바 코드 안에서 직접 SQL 돌리고 트랜잭션 관리.

**장점**: 단순. 의존성 거의 없음.

**단점**: 위 4가지 요구사항을 *전부 직접* 짜야 함:
- 메모리 안전 → 페이징 SQL 직접 작성 + 결과 스트리밍
- 재처리 → 멱등성을 어떻게 보장할지 매번 고민
- 이력 추적 → 별도 로그 테이블 만들고 매 단계 INSERT
- 트랜잭션 경계 → chunk 같은 표준 패턴이 없으니 직접 설계

규모가 작은 일회성 잡엔 OK, 정산처럼 **정기적이고 무결성이 중요한** 잡에는 부적합.

#### (B) Apache Airflow

**무엇**: 잡 간 의존성과 스케줄을 **DAG**(Directed Acyclic Graph, 순환 없는 작업 그래프)로 정의하고, **분산 워커**(여러 머신·프로세스가 작업을 나눠 처리)들에 태스크를 할당해 실행하는 워크플로우 엔진. Python으로 DAG 작성.

**장점**:
- DAG 시각화 UI ("이 잡은 저 잡 끝나야 시작")
- 분산 처리 (수십~수백 워커 풀)
- 스케줄링·재시도·SLA·알림 다 내장
- 빅데이터 파이프라인의 사실상 표준

**단점**:
- 별도 인프라 운영: 메타DB + 스케줄러 + 워커 풀
- 단일 잡 내부의 **트랜잭션 일관성은 별도로 짜야 함** — Airflow는 "이 태스크 돌려" 수준이지 "한 트랜잭션 안에서 chunk 단위 commit해" 수준이 아님
- 정산처럼 *DB 트랜잭션이 본질*인 도메인엔 오버킬

#### (C) Spring Batch

**무엇**: 자바 진영의 표준 ETL(Extract-Transform-Load) 프레임워크. **단일 JVM 안에서** 데이터를 페이지 단위로 읽어 변환하고 저장하는 표준 패턴(Job/Step/Reader/Processor/Writer/Chunk)을 제공.

**장점**:
- 위 4가지 요구사항이 전부 표준 — 코드 한 줄(`.faultTolerant().skipLimit(10)`)이면 적용
- 운영 DB와 **같은 트랜잭션 매니저를 공유** → 비즈 결과와 메타데이터가 한 트랜잭션에 묶임
- Spring 생태계 자연 통합 (DI, JPA, 스케줄러 등)

**단점**:
- 분산 처리는 약함 (Partitioning은 있지만 Airflow만큼 본격적이지 않음)
- 운영 UI 없음 (Spring Cloud Data Flow는 별도 프로젝트)

### 1.3 결론

본 프로젝트의 시나리오는:

> **단일 JVM에서, OLTP DB와 같은 트랜잭션 매니저를 공유하는, 정기 후속 배치.**

Airflow의 분산성은 오버킬이고, cron의 단순함은 무결성을 직접 떠받치게 만든다. 정산이 요구하는 4가지 — 무결성·재처리·메모리 안전·이력 — 가 모두 표준으로 들어 있는 **Spring Batch가 정답**.

## 2. 우리가 고른 패턴 — 트레이드오프

각 패턴의 *어휘 정의*는 02에 있다. 여기서는 *왜 그 값/방식을 골랐고, 무엇을 잃었나*에 집중한다.

### 2.1 chunk size = 1000

너무 작으면(예: 10):
- 트랜잭션이 짧아 부분 실패 영향이 작음
- 하지만 commit 횟수가 100배 → DB 왕복 비용 폭증

너무 크면(예: 100,000):
- I/O 효율은 좋아짐
- 하지만 한 번 실패하면 100,000건 통째로 롤백
- 메모리 점유도 커짐

**1000은 산업 표준 sweet spot**. 우리 시드(35,000)에서 `BATCH_STEP_EXECUTION.commit_count`가 Job 1은 4, Job 2/3는 6으로 실제로 multi-chunk 동작 확인됨.

**잃은 것**: 1000건 단위 부분 실패 가능. 그래서 §2.3 skip 정책이 짝꿍.

### 2.2 SQL-level GROUP BY (02 §6.1과 같은 결정)

02 §6.1에서 *왜* 이 결정인지는 다뤘으니, 여기서는 *그 결과로 잃은 것*에만 집중:

- **DB 부하가 reader 단계에 집중**: 거래 1억 건이 쌓인 큰 테이블이면 GROUP BY 자체가 느려질 수 있음. `transaction_date` 인덱스가 잘 깔려 있어야 함 (V1 스키마에 `idx_tx_status_date`로 들어가 있음).
- **Processor에서 거래 1건씩의 분기 로직이 어렵다**: 예를 들어 "이체일 때만 환율 변환"같이 거래 1건씩 외부 호출이 필요하면 GROUP BY 패턴이 안 맞음. 그땐 row-level streaming + chunk 경계 충돌 처리(누적 emit 등)가 필요.

본 프로젝트는 거래 1건씩의 분기 로직이 없으므로 GROUP BY가 자연스러운 선택.

### 2.3 faultTolerant + skipLimit(10) + skip(IllegalStateException)

```java
.faultTolerant()
.skipLimit(10)
.skip(IllegalStateException.class)
```

**왜 skip이 필요한가**: 데이터 1건의 결함(예: 알 수 없는 계좌)이 10000건짜리 잡 전체를 죽이면 안 됨. "그 1건만 빼고 나머지 처리"가 운영 친화적.

**왜 10이라는 한계**:
- 너무 작으면(예: 1): 진짜 결함 1건에서 잡이 멈춤 → 운영자가 매번 개입
- 너무 크면(예: 1만): 데이터 품질 문제를 가려버림 — 절반이 깨졌는데도 잡이 성공으로 끝남
- 10이 "운영 노이즈는 흡수, 진짜 사고는 멈춤"의 절충

**왜 IllegalStateException만**: 너무 광범위한 예외(`Exception.class`)를 skip하면 NPE 같은 코드 버그도 조용히 넘어가버림. 본 프로젝트는 *예상 가능한 데이터 결함*만 명시적으로 IllegalStateException으로 던지고, 그것만 skip 대상으로 등록.

**잃은 것**: skip된 건은 어딘가에 따로 처리해야 함. `BATCH_STEP_EXECUTION.skip_count`로 추적은 되지만, 운영에서는 skip 로그 → 재처리 큐 같은 패턴이 별도 필요. 본 프로젝트는 skip 카운트만 기록.

### 2.4 RunIdIncrementer + cleanupStep (멱등성 패턴)

02 §2.3 + §6.2에서 *왜*는 다뤘으니, 여기서는 *대안과의 비교*:

| 대안 | 방식 | 본 프로젝트가 선택? |
|---|---|---|
| **Step-level restart** | Spring Batch가 메타데이터에 마지막 commit chunk 위치를 저장. 같은 JobInstance를 재실행하면 그 다음 chunk부터 재개 | X |
| **새 JobInstance + cleanup** | 매 실행마다 새 JobInstance(RunIdIncrementer로 run.id 증가) + 첫 Step에서 기존 결과 삭제(cleanupStep) → 매번 처음부터 다시 | ✓ |

본 프로젝트가 후자를 고른 이유:
- Job들이 가볍다 (수십만 건 미만 / 수 초 수준) → 처음부터 다시 도는 비용이 작음
- cleanup이 단일 SQL 한 줄로 깔끔 → 멱등성을 직접 보장하기 쉬움
- Step-level restart는 ItemReader가 자기 위치를 메타데이터에 저장하는 stateful 인터페이스를 구현해야 함 → 복잡도 ↑, 디버깅 어려움

**잃은 것**: JobInstance가 매번 늘어남. 메타데이터 정리 정책이 별도로 필요 (예: 30일 이상 된 BATCH_JOB_EXECUTION 삭제하는 housekeeping 잡).

### 2.5 JPA `ddl-auto = validate`

Spring Data JPA의 `spring.jpa.hibernate.ddl-auto` 옵션 4가지:

| 값 | 동작 |
|---|---|
| `create` | 부팅 시 모든 테이블 DROP & CREATE — 데이터 다 날아감 |
| `update` | 엔티티 변경에 맞춰 ALTER TABLE 자동 발행 — 운영에서 위험 |
| `validate` | 엔티티 ↔ 실제 스키마 일치 여부만 검증, 자동 변경 X |
| `none` | 아무 것도 안 함 |

**우리 선택**: `validate`. 운영 안전성을 위해 자동 변경을 막고, 검증만 한다. 엔티티가 스키마와 어긋나면 부팅 자체가 실패해 사람이 인지하게 됨.

**현재 마이그레이션**: `src/main/resources/db/migration/V1__init_schema.sql`이 docker-compose의 `docker-entrypoint-initdb.d`를 통해 MySQL 컨테이너 초기 부팅 시 1회 적용.

**잃은 것**: 진짜 마이그레이션 도구(Flyway/Liquibase)는 도입 안 함. V1 단일 스크립트만 사용. 운영 단계로 가면 Flyway 도입이 다음 자연 step.

## 3. 의도적으로 안 쓴 패턴

각 패턴이 *무엇인지* 풀어 쓰고, *왜 안 썼는지* 정리.

### 3.1 Partitioning (멀티스레드 Step)

**무엇**: 한 Step의 처리 범위를 N개 partition으로 쪼개고, 각 partition을 별도 스레드(또는 별도 JVM)에서 동시에 처리하는 Spring Batch 기능. 예: account_number의 끝자리(0~9)로 10개 partition 나눠 10개 스레드가 병렬 처리.

**언제 쓰면 좋은가**: 단일 노드에서 데이터 처리량이 부족할 때, 또는 분산 처리 인프라 위에 올려야 할 때.

**우리가 안 쓴 이유**:
- 시드 35,000건 / 운영 가정 수십만 건 — 단일 스레드로 충분
- SQL-level GROUP BY가 이미 DB에서 한 번에 끝남 → Java 멀티스레드의 이점이 거의 없음
- 멀티스레드는 디버깅·로그·트랜잭션 경계가 복잡해짐. 처리량이 진짜 병목이 될 때 도입.

### 3.2 Async ItemProcessor

**무엇**: Processor가 비동기로 외부 호출(API/외부 DB 등)을 하면서 chunk throughput을 높이는 패턴. 동기 호출이 100ms씩 걸리면 1000건 = 100초이지만, 비동기 + 워커풀이면 수 초.

**언제 쓰면 좋은가**: Processor가 외부 I/O 바운드 작업을 할 때 (예: 외부 환율 API 호출, 외부 검증 서비스).

**우리가 안 쓴 이유**:
- 본 프로젝트의 Processor는 로컬 DB 조회 + 단순 변환만 — 외부 I/O 없음
- 비동기 추가하면 트랜잭션 경계 관리가 복잡 → ROI 없음

### 3.3 Step-level restart from execution context

**무엇**: Spring Batch가 메타데이터(`BATCH_STEP_EXECUTION_CONTEXT`)에 ItemReader의 진행 위치(예: 마지막 page number)를 자동 저장. 같은 JobInstance를 재실행하면 그 위치부터 재개.

**언제 쓰면 좋은가**: 잡이 무거워서(수 시간) 처음부터 다시 돌리는 비용이 큰 경우, 또는 처리 도중에 외부 부수효과가 발생해서 처음부터 다시 돌리면 곤란한 경우.

**우리가 안 쓴 이유**:
- 우리는 RunIdIncrementer + cleanupStep으로 *항상 새 JobInstance에서 처음부터* 도는 패턴 → restart 자체가 의미 없음
- Job들이 짧고 가벼워서 처음부터 다시 돌려도 비용이 작음
- Reader가 stateful 인터페이스를 구현해야 함 → 복잡도 ↑

## 4. Spring Batch Metadata 활용

Spring Batch는 부팅 시 자동으로 메타데이터 테이블을 만들고, 매 실행마다 자동으로 채운다 (`spring.batch.jdbc.initialize-schema=always` 설정). 이게 운영에서 가장 큰 이점 중 하나.

### 4.1 자동 생성되는 테이블

| 테이블 | 담는 정보 |
|---|---|
| `BATCH_JOB_INSTANCE` | "Job 이름 + identifying JobParameters" 조합의 인스턴스 (식별자 역할) |
| `BATCH_JOB_EXECUTION` | 한 번의 실행 시도 (status, start/end time, exit code) |
| `BATCH_JOB_EXECUTION_PARAMS` | 그 실행에 들어간 JobParameters의 실제 값 |
| `BATCH_STEP_EXECUTION` | 각 Step의 실행 통계 (read_count, write_count, skip_count, commit_count) |
| `BATCH_STEP_EXECUTION_CONTEXT` | Step 진행 위치 (restart용 — 우리는 안 씀) |

### 4.2 우리 시드에서 본 실제 값

```sql
SELECT step_name, status, read_count, write_count, commit_count
FROM BATCH_STEP_EXECUTION
ORDER BY step_execution_id DESC LIMIT 9;
```

```
step_name              status     read_count  write_count  commit_count
closingStep            COMPLETED  5000        5000         6
closingCleanupStep     COMPLETED  0           0            1
journalGenerationStep  COMPLETED  5000        5000         6
journalCleanupStep     COMPLETED  0           0            1
aggregationStep        COMPLETED  3196        3196         4
cleanupStep            COMPLETED  0           0            1
...
```

이 한 번의 SQL이 보여주는 것:
- Job 1 aggregationStep이 3196건을 4개 chunk로 commit (1000+1000+1000+196)
- Job 2/3가 5000건을 6개 chunk (1000×5 + 마지막 빈 chunk 1)
- cleanupStep들은 read/write 0, commit 1 (Tasklet은 단일 트랜잭션)

코드 어디에도 이 통계를 직접 기록하는 줄이 없다. Spring Batch가 자동으로 채운 결과.

### 4.3 운영에서 활용 패턴

| 활용 | SQL/방법 |
|---|---|
| **실패 알림** | `WHERE status='FAILED'` 모니터링 → Slack/PagerDuty webhook |
| **처리량 추적** | `read_count` 시계열 → "거래량이 어제 대비 30% 줄었네" 같은 신호 |
| **품질 추적** | `skip_count > 0` → 데이터 품질 이슈 → 별도 큐로 보내 재처리 |
| **재실행 결정** | 실패한 JobInstance 발견 → 코드 fix → 같은 settlementDate로 재실행 (cleanupStep이 부분 결과 정리) |
| **메타데이터 정리** | 30일 이상 된 BATCH_JOB_EXECUTION row 삭제하는 housekeeping 잡 (실제로는 archival 후 삭제) |

## 5. 트레이드오프 정리

| 결정 | 얻은 것 | 잃은 것 / 한계 |
|---|---|---|
| **chunk = 1000** | DB 왕복 효율 + 짧은 트랜잭션 사이의 sweet spot | 1000건 단위 부분 실패 — skip 정책으로 보완 |
| **SQL-level GROUP BY** | Processor가 stateless 1:1 매핑이라 chunk 경계 문제 원천 차단, 정확성 검증 쉬움 | DB 부하가 reader paging에 집중. 1억 건 규모면 인덱스가 결정적 |
| **faultTolerant + skipLimit(10)** | 데이터 1건 결함이 잡 전체를 안 죽임. 운영 친화적 | skip된 데이터는 별도 재처리 파이프라인이 필요 |
| **RunIdIncrementer + cleanup** | 동일 파라미터 재실행 가능, 멱등성 구현 단순 | JobInstance가 매번 늘어 메타데이터 정리 정책 필요 |
| **`validate` ddl-auto** | prod 안전성, 엔티티-스키마 어긋남 즉시 탐지 | 마이그레이션 도구 필수. 현재 V1 스크립트로 충분, 운영 가면 Flyway 도입 권장 |

---

여기까지가 *무엇을 만들었는가* 와 *왜 그렇게 결정했는가* 의 이야기. *어떻게 만들어졌는가* — 이 코드를 빌드하면서 활용한 개발 방법론은 다음 문서에서 다룬다 → [04-harness.md](04-harness.md).
