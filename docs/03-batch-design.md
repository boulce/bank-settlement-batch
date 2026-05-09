## 3. Spring Batch 설계 결정

[02-architecture.md](02-architecture.md)에서 정리한 컴포넌트들을 묶고 있는 코어가 Spring Batch다. 본 문서는 "왜 Spring Batch였는지"와 "그 안의 어떤 기능을 어떤 이유로 어떻게 썼는지"를 정리한다.

## 왜 Spring Batch (vs. Airflow / cron + 일반 Spring 코드)

| 비교 | 대안 | 본 프로젝트 선택 이유 |
|---|---|---|
| Airflow | DAG로 잡 의존성·스케줄 관리 | 분산 워커가 필요한 빅데이터 파이프라인에 강점. **본 프로젝트는 단일 JVM 내 트랜잭션 일관성이 더 중요**, OLTP DB와 같은 트랜잭션 매니저 공유로 부분 실패 시 일관된 롤백/재시작이 핵심 |
| cron + 일반 코드 | 단순한 스크립트 실행 | **재시작/스킵/체크포인트가 표준 미제공**. 1만 건 처리 중 5천 건째 실패 시 처음부터 다시 돌리거나, 직접 체크포인트 로직을 구현해야 함 |
| Spring Batch | 검증된 ETL 프레임워크 | (1) Chunk 단위 트랜잭션, (2) JobRepository에 자동 메타데이터 저장으로 재시작 지점 추적, (3) Skip/Retry 정책이 builder에 한 줄, (4) Spring 생태계 자연 통합 |

특히 정산 도메인은 **무결성과 재처리**가 핵심이라 Spring Batch가 적합하다.

## 사용한 핵심 패턴

### 1. Chunk-oriented Processing (Reader → Processor → Writer)

```
   ┌────────┐   item   ┌──────────┐   item    ┌────────┐
   │ Reader │ ───────► │ Processor│ ────────► │ Writer │
   └────────┘          └──────────┘           └────────┘
                          반복
                          (chunk=1000건마다 commit)
```

- **Reader**: `JpaPagingItemReader` — 한 번에 메모리 폭발하지 않게 page 단위 fetch
- **Processor**: 1:1 변환 (또는 1:0 = 필터)
- **Writer**: chunk 모이면 한 번에 commit

본 프로젝트는 **chunk size = 1000**. DB I/O 효율과 트랜잭션 길이 사이의 절충.

### 2. Tasklet (단일 작업)

Job 1의 `cleanupStep`은 chunk가 아닌 단일 SQL `DELETE`라 Tasklet으로 작성:

```java
@Bean
@StepScope
public Tasklet cleanupTasklet(@Value("#{jobParameters['settlementDate']}") String dateStr) {
    return (contribution, chunkContext) -> {
        long deleted = summaryRepository.deleteBySettlementDate(LocalDate.parse(dateStr));
        log.info("이전 summary {} 건 삭제", deleted);
        return RepeatStatus.FINISHED;
    };
}
```

**규칙**: 반복 처리(=chunk) 패턴이 자연스럽지 않으면 Tasklet이 정답. 본 프로젝트의 cleanup이 그 케이스.

### 3. JobParameters와 SpEL `@StepScope`

`settlementDate`는 매 실행마다 다른 외부 입력. Spring Batch는 이를 `JobParameters`로 받고, late binding으로 reader/processor에 주입한다:

```java
@StepScope
public Tasklet cleanupTasklet(@Value("#{jobParameters['settlementDate']}") String dateStr)
```

- **`@StepScope` 필수**: 빈이 Job 시작 시점에 새로 생성되어야 SpEL 평가가 가능
- **Step 빈 자체에 @StepScope를 부착하면 안 됨**: SimpleJob이 step 이름 조회 시 ScopeNotActiveException 발생 (Sprint 01 1차 평가에서 발견된 버그)

### 4. Skip Policy (faultTolerant)

```java
.faultTolerant()
.skipLimit(10)
.skip(IllegalStateException.class)
```

- 데이터 1건의 결함이 10000건짜리 잡 전체를 죽이면 안 됨
- 본 프로젝트는 "알 수 없는 계좌"를 만났을 때 `IllegalStateException` 발생 → 최대 10건까지 스킵
- 스킵된 건은 `BATCH_STEP_EXECUTION.SKIP_COUNT`로 추적, 운영에서는 알림 트리거 가능

### 5. RunIdIncrementer + 멱등성 Tasklet

```java
new JobBuilder("...")
    .incrementer(new RunIdIncrementer())
    .start(cleanupStep)
    .next(aggregationStep)
```

- `RunIdIncrementer`: 매 실행마다 `run.id` 파라미터를 증가시켜 **새로운 JobInstance**를 만든다 → 같은 settlementDate로 재실행해도 `JobInstanceAlreadyCompleteException` 회피
- 비즈 테이블의 멱등성은 `cleanupStep`이 보장 (Spring Batch 자체는 비즈 부수효과 멱등성을 보장하지 않음)

### 6. Spring Batch Metadata 테이블

- `spring.batch.jdbc.initialize-schema=always` → 첫 부팅 시 BATCH_* 테이블 자동 생성
- Job 실행 이력은 `BATCH_JOB_EXECUTION`, Step 단위 통계는 `BATCH_STEP_EXECUTION` (READ/WRITE/SKIP/COMMIT count)
- 실패 시 같은 JobInstance를 재시작하면 마지막 commit된 chunk 다음부터 재개

## 의도적으로 사용하지 않은 패턴

- **Partitioning** (멀티스레드 step): 단일 노드 내 처리량은 SQL-level GROUP BY로 충분. 분산이 필요한 데이터 규모가 아님.
- **Async ItemProcessor**: I/O 바운드 호출(외부 API)이 없어서 도입 이유 없음.
- **Job-level Restart with execution context**: 본 프로젝트의 Job들은 멱등성 cleanup 패턴으로 처음부터 재실행하는 것이 더 단순. Step 중단점 재개는 메타데이터 보존만 활용.

## 트레이드오프 정리

| 결정 | 얻은 것 | 잃은 것 / 한계 |
|---|---|---|
| chunk=1000 | DB 왕복 ↓, 트랜잭션 짧음 | 1000건 단위로 부분 실패 발생 가능 (skip 정책으로 보완) |
| SQL-level GROUP BY | 단순한 processor, 정확성 ↑ | DB 부하가 reader에 집중. 매우 큰 테이블이면 paging이 느려질 수 있음 |
| RunIdIncrementer | 동일 파라미터 재실행 가능 | JobInstance가 매번 늘어나 메타데이터 정리 정책 필요 |
| `validate` ddl-auto | prod 안전성 | 마이그레이션 도구 필수 (현재는 V1 단일 스크립트, 추후 Flyway 도입 권장) |

---

여기까지가 *무엇을 만들었는가* 의 이야기다. *어떻게 만들어졌는가* — 즉 이 코드를 빌드하면서 활용한 개발 방법론은 다음 문서에서 다룬다 → [04-harness.md](04-harness.md).
