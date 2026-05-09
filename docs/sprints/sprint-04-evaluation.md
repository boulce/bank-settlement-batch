# Sprint 04 Evaluation: Scheduler + Restart 개념 정리

평가 일시: 2026-05-09 23:25 KST
대상 contract: `docs/sprints/sprint-04-scheduler.md`
평가 차수: 1차

## Contract 항목별 판정

### [PASS] A1. `--spring.profiles.active=scheduled` 부팅 시 SettlementScheduler 활성화
**증거**: `SettlementScheduler.java`에 `@Profile("scheduled")` + `@Component` 부착. Spring이 활성 프로필이 일치할 때만 빈으로 등록. 다른 프로필(default, seed, test)에서는 비활성.
컴포넌트 구조 + `@SpringBootApplication` + `@EnableScheduling` (`BankSettlementBatchApplication.java:10`) 조합으로 cron 트리거 동작 가능.

### [PASS] A2. Cron 표현식 의도와 일치
**증거**:
- 일일 집계: `cron = "0 0 2 * * *"`, zone Asia/Seoul → 매일 02:00 KST.
- 월별 마감: `cron = "0 0 3 1 * *"`, zone Asia/Seoul → 매월 1일 03:00 KST.

### [PASS] A3. JobLauncher 직접 호출 가능
**증거**: `runDailyAggregation()`/`runMonthlyClosing()`이 `JobRegistry`로 Job을 조회하고 `JobLauncher.run`으로 실행. 메서드 시그니처가 `public`이며 cron 외에도 메서드 직접 호출(테스트 시 IDE에서 즉시 트리거)도 가능.

### [PASS] A4. 비활성 프로필에서 자동 트리거 없음
**증거**: 본 sprint 이전(`default` 프로필)에서 Job1/Job2/Job3을 명시적 `--job=` 인자 없이 부팅하지 않음. `@Profile("scheduled")` 가드로 `default`에서는 SettlementScheduler 빈 자체가 컨텍스트에 없음. `./gradlew test`(test 프로필)도 정상 통과.

## 빌드 검증

`./gradlew compileJava test` → `BUILD SUCCESSFUL`. ContextLoad 테스트가 scheduler 변경 후에도 정상 → 빈 와이어링 무결.

## 종합 판정

**RESULT: PASS** (cron 실시간 발화 검증은 운영 시점의 자연 검증 영역으로 위임)

## 후속 권고

- 프로덕션에서는 `ShedLock`(분산 lock)으로 멀티 인스턴스에서도 단 한 번만 트리거되도록 보장.
- 잡 실패 시 알림 channel(`@JobExecutionListener.afterJob`에서 Slack webhook 호출 등) 추가.
- 자동 재시도 정책 (예: 실패 시 30분 후 1회 자동 retry)을 별도 sprint로 다룰 수 있음.
