#!/usr/bin/env bash
# 전체 정산 파이프라인 한 번에 실행 (시드부터 월별 마감까지).
# 데모/포트폴리오 시연용. 운영에서는 SettlementScheduler가 같은 일을 자동 트리거한다.
set -euo pipefail

cd "$(dirname "$0")/.."

# 시드 anchor 날짜와 일치해야 함 (application-seed.properties: seed.anchor-date=2026-05-09)
DATES=(2026-05-02 2026-05-03 2026-05-04 2026-05-05 2026-05-06 2026-05-07 2026-05-08)
YEAR_MONTH="2026-05"
DEMO_DATE="${1:-2026-05-05}"  # 분개 데모로 보일 날짜

echo "=== 1) MySQL 컨테이너 기동 ==="
docker compose up -d
until docker compose exec -T mysql mysqladmin ping -uroot -psettlement1234 2>/dev/null | grep -q "alive"; do sleep 2; done
echo "MySQL ready"

echo
echo "=== 2) 시드 데이터 생성 ==="
./gradlew --quiet bootRun --args='--spring.profiles.active=seed' >/dev/null

echo
echo "=== 3) 7일치 일별 거래 집계 (Job 1) ==="
for d in "${DATES[@]}"; do
  echo "  -> $d"
  ./gradlew --quiet bootRun --args="--job=dailyTransactionAggregationJob --settlementDate=$d" >/dev/null
done

echo
echo "=== 4) ${DEMO_DATE} 분개 생성 (Job 2 데모) ==="
./gradlew --quiet bootRun --args="--job=journalEntryGenerationJob --entryDate=${DEMO_DATE}" >/dev/null

echo
echo "=== 5) ${YEAR_MONTH} 월별 마감 (Job 3) ==="
./gradlew --quiet bootRun --args="--job=monthlyClosingJob --yearMonth=${YEAR_MONTH}" >/dev/null

echo
echo "=== 6) 결과 요약 ==="
docker compose exec -T mysql mysql -uroot -psettlement1234 settlement_db <<EOF
SELECT 'transactions'                       AS table_name, COUNT(*) AS row_count FROM transactions
UNION ALL SELECT 'daily_transaction_summaries', COUNT(*) FROM daily_transaction_summaries
UNION ALL SELECT 'journal_entries (${DEMO_DATE})', COUNT(*) FROM journal_entries WHERE entry_date='${DEMO_DATE}'
UNION ALL SELECT 'monthly_account_summaries (${YEAR_MONTH})', COUNT(*) FROM monthly_account_summaries WHERE year_month_key='${YEAR_MONTH}';

SELECT
  '복식부기 invariant: SUM(debit)' AS invariant, SUM(amount) AS value
  FROM journal_entries WHERE entry_date='${DEMO_DATE}' AND entry_type='DEBIT'
UNION ALL SELECT
  'SUM(credit) — 위와 같아야 함', SUM(amount)
  FROM journal_entries WHERE entry_date='${DEMO_DATE}' AND entry_type='CREDIT';
EOF
echo
echo "=== 파이프라인 완료 ==="
