# Sprint 04 — Scheduler + 실패/재시작 시나리오

## 배경

운영 환경에서는 잡을 사람이 매일 새벽 손으로 트리거하지 않는다. 정기 스케줄러가 자동 실행하고, 실패 시 알림 → 수동/자동 재시작이 표준이다.

본 sprint는 두 가지를 다룬다:
1. **인-프로세스 스케줄러** (`SettlementScheduler`): cron으로 daily/monthly 잡 자동 트리거.
2. **Spring Batch 재시작 메커니즘**: 실패한 Job이 어떻게 재시작 가능한지 (개념 + 수동 검증 절차).

## 1. Scheduler

### 컴포넌트

`com.finance.settlement.batch.SettlementScheduler`
- `@Profile("scheduled")` — 부팅 시 `--spring.profiles.active=scheduled`일 때만 활성화
- `@Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")` — 매일 02:00 (KST) 일일 집계 실행 (어제 날짜)
- `@Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Seoul")` — 매월 1일 03:00 (KST) 월별 마감 실행 (직전 달)

### Acceptance Criteria

- **A1**: `--spring.profiles.active=scheduled` 부팅 시 SettlementScheduler bean이 등록된다.
- **A2**: cron 표현식이 의도한 시각(02:00 일일 / 03:00 월초)에 맞다.
- **A3**: 두 스케줄 메서드 모두 JobLauncher를 통해 정상 트리거 가능 (메서드 직접 호출로 스모크 테스트 가능).
- **A4**: 다른 프로필(default, seed)에서는 SettlementScheduler가 활성화되지 않아 자동 트리거가 발생하지 않는다.

### Definition of Done

- A1, A4: bean 활성/비활성을 부팅 로그로 확인.
- A2: 코드 인스펙션.
- A3: 사용자가 `--spring.profiles.active=scheduled`로 부팅 후 일정 시간 내 자동 실행 로그 확인 (실시간 cron 대기 대신 메서드 직접 호출 권장).

## 2. Spring Batch 재시작 메커니즘 (개념)

Spring Batch는 다음 메커니즘으로 재시작을 지원한다:

1. **JobInstance 식별성**: 같은 (job name, identifying parameters) 조합은 동일 JobInstance를 가리킨다.
2. **Step ExecutionContext**: chunk 단위 commit마다 reader의 progress(예: page index)가 metadata에 저장된다.
3. **재실행 시 재개**: 실패한 JobInstance를 같은 파라미터로 재실행하면, Spring Batch는 마지막 commit된 chunk 다음부터 시작한다.

본 프로젝트는 Job마다 `RunIdIncrementer`를 사용한다. `run.id`가 `identifying=true` 파라미터로 추가되므로 매 실행마다 새로운 JobInstance가 만들어진다.

따라서 본 프로젝트에서의 "재시작"은 두 가지 형태로 이해할 수 있다:
- (a) **새 JobInstance로 처음부터 재실행**: `RunIdIncrementer` 효과. 실패한 잡을 동일 파라미터로 재시도해도 항상 새 instance가 만들어지고, `cleanupStep` Tasklet이 비즈 멱등성을 보장한다.
- (b) **동일 JobInstance에서 재개**: `RunIdIncrementer`를 빼고 같은 파라미터로 재실행하는 시나리오. Spring Batch가 마지막 chunk 이후부터 처리. 본 프로젝트의 사이즈에서는 (a)가 충분히 빠르고 단순하므로 채택.

### 수동 검증 절차 (선택)

1. `TransactionAggregationItemProcessor`에 일시적으로 처음 5번째 호출에서 `RuntimeException`을 던지는 코드 삽입.
2. Job 실행 → FAILED.
3. 코드 원복 후 같은 파라미터로 재실행 → cleanupStep이 부분 결과 삭제 → COMPLETED.
4. 결과 row 수가 정상 시 동일함을 확인.

본 sprint에서는 시간 제약으로 자동화된 재시작 테스트 코드는 작성하지 않고, 위 절차를 문서화하는 것으로 한정한다.

## 한계와 후속 작업

- **장애 알림**: 본 프로젝트는 잡 실패 시 로그만 남긴다. 운영에서는 Slack/PagerDuty 같은 외부 알림 채널을 listener에서 호출해야 한다.
- **분산 lock**: 인-프로세스 `@Scheduled`는 단일 JVM 가정. 멀티 인스턴스 환경에서는 ShedLock 등으로 단 하나만 트리거되도록 보장 필요.
- **회복 자동화**: 실패한 Job을 일정 시간 후 자동 재시도하는 정책은 본 sprint 범위 외.
