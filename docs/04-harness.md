# Claude Code 하네스 활용

본 문서는 이 프로젝트가 어떻게 Claude Code의 하네스 엔지니어링 기능들을 코딩 워크플로우에 녹여 썼는지 정리한다. 단순 자동화를 넘어 **생성자(Generator) — 평가자(Evaluator) 분리** 패턴을 적용한 것이 핵심.

## 적용한 두 축

### 축 1: PostToolUse / Stop Hooks — 자동 검증 루프

`.claude/settings.json`에 정의된 훅이 코드 변경 시점마다 자동 실행된다.

```json
{
  "hooks": {
    "PostToolUse": [
      { "matcher": "Edit|Write",
        "hooks": [{ "type": "command",
                    "command": "... compileJava ...",
                    "timeout": 60 }] },
      { "matcher": "Edit|Write",
        "hooks": [{ "type": "command",
                    "command": "... test ...   (테스트 파일에만 발동)" }] }
    ],
    "Stop": [
      { "hooks": [{ "type": "command",
                    "command": "... ./gradlew build ..." }] }
    ]
  }
}
```

발동 조건:
- **Edit/Write 직후 .java 변경**: `./gradlew compileJava` 자동 실행 → 컴파일 깨지면 즉시 알 수 있음
- **Edit/Write 직후 테스트 파일 변경**: `./gradlew test` 자동 실행
- **세션 종료 시(Stop)**: 전체 빌드 검증 (`./gradlew build -x test`)

이로써 "수정 → 컴파일 안 해보고 다음 파일로 넘어가서 누적 오류" 패턴을 차단한다. 블로그 본문에서 언급된 "Ralph Wiggum" 류 자동화에 해당.

### 축 2: 평가자 서브에이전트 — 회의적 검증자

블로그의 핵심 통찰: **에이전트는 자기 작업을 평가할 때 관대하다**. 이를 깨려면 별도의 회의적 평가자를 둬야 한다.

`.claude/agents/batch-evaluator.md`에 평가자 페르소나를 정의:
- 자기 검증 금지 — 반드시 실제 Job 실행 + DB 쿼리로 검증
- 통과 기본값은 NO. 통과시키려면 SQL/로그/코드 라인 인용 증거 필요
- contract에 없어도 멱등성·재시작·금액 정확성은 항상 점검

각 sprint마다:
1. **Sprint contract 작성** (`docs/sprints/sprint-XX-NAME.md`) — acceptance criteria 명시
2. **Generator(메인 에이전트)가 구현** + Hooks가 컴파일/테스트 자동 검증
3. **Evaluator 서브에이전트 디스패치** — contract와 실제 동작 대조
4. **FAIL 항목 수정 → 재평가** — PASS 될 때까지

## 실제로 어떤 일이 일어났나 (Sprint 01 사례)

```
1. 사용자가 Job 1 사양 요청
2. 메인 에이전트가 Reader/Processor/Writer/JobConfig 코드 작성
   ⇒ 변경 시점마다 PostToolUse 훅이 compileJava 실행
3. Sprint contract (acceptance criteria 14개) 작성
4. Evaluator 서브에이전트 1차 평가
   ⇒ 11개 항목 FAIL 적발:
     - @StepScope를 Step bean에 잘못 부착 → ScopeNotActiveException
     - chunk 내 동일 계좌 N건 → 동일 entity N번 반환 → UNIQUE 위반 위험
     - openingBalance가 현재 잔액 사용 (의미 오류)
     - 멱등성 미구현 (재실행 시 UNIQUE 위반)
     - JobLauncher 활성화 누락 → --spring.batch.job.name 무력화
     ... 등
5. 메인 에이전트가 결함 11개 일괄 수정 (SQL-level GROUP BY로 재설계,
   JobInvoker 추가, cleanupStep + RunIdIncrementer 도입, 잔액 컬럼 드랍)
   ⇒ Hooks가 매 변경마다 compileJava
6. Evaluator 2차 평가
   ⇒ 모든 항목 PASS, RESULT: PASS
7. Git 커밋 (sprint contract + evaluation report 같이 포함)
```

블로그가 강조한 것: "평가자가 외부에 있으면 generator가 그것을 향해 iterate할 구체적인 피드백 표면이 생긴다." Sprint 01의 11→0 결함 수렴이 정확히 그 효과.

## 차별점 — 블로그 패턴 vs. 본 프로젝트

| 블로그 (full agent harness) | 본 프로젝트 |
|---|---|
| Planner agent (1줄 → 풀 spec) | 사용자 + 메인 에이전트 협의로 spec 작성 (수동) |
| Generator agent | 메인 Claude Code 세션 |
| Evaluator agent (Playwright MCP로 실제 클릭) | `batch-evaluator.md` 서브에이전트 (DB 쿼리로 검증) |
| Sprint contract 사전 협상 (frontend) | sprint-XX 문서를 generator-evaluator 공통 기준으로 사용 |
| Context reset 매 sprint마다 | 본 프로젝트는 Opus 4.7로 단일 세션 유지 (블로그 후반부의 단순화 방향과 일치) |

## 한계와 다음 단계

- **Planner 에이전트 미적용**: 사양은 사용자/메인 에이전트가 직접 만든다. 다음 단계 후보.
- **Evaluator의 도구 셋 한정**: Playwright 같은 실제 인터랙션 없음 (배치 시스템엔 UI가 없으므로 자연스러운 한계).
- **Hook timeout/staleness**: gradle 데몬이 잠들어 있으면 첫 compileJava 훅이 timeout=60s를 넘길 수 있음. 워크어라운드로 데몬을 미리 워밍업.

## 시도해본 다른 패턴 (참고)

- **Ralph Wiggum loop**: 블로그가 언급. 본 프로젝트도 PostToolUse 훅이 그 변형.
- **컨텍스트 리셋 + 인계 artifact**: 적용하지 않음. Opus 4.7의 컨텍스트 안정성으로 단일 세션이 충분.

## 참고

- 블로그 글: *Harness design for long-running application development* (Anthropic, 2026-03-24)
- Claude Code Hooks 공식 문서: settings.json `hooks` 섹션
