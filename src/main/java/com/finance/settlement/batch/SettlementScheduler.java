package com.finance.settlement.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 정기 실행 스케줄러.
 * `--spring.profiles.active=scheduled`로 부팅했을 때만 활성화된다.
 * 운영에서는 별도의 cron/Airflow가 트리거해도 되고, 본 컴포넌트로 인-프로세스 트리거도 가능.
 */
@Slf4j
@Component
@Profile("scheduled")
@RequiredArgsConstructor
public class SettlementScheduler {

    private final JobLauncher jobLauncher;
    private final JobRegistry jobRegistry;

    /**
     * 매일 02:00 (Asia/Seoul) — 어제 날짜의 일일 거래 집계 실행
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void runDailyAggregation() throws Exception {
        LocalDate target = LocalDate.now().minusDays(1);
        log.info("[Scheduler] 일일 거래 집계 트리거 — {}", target);

        Job job = jobRegistry.getJob("dailyTransactionAggregationJob");
        JobExecution exec = jobLauncher.run(job, new JobParametersBuilder()
                .addString("settlementDate", target.toString())
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());

        log.info("[Scheduler] 일일 거래 집계 결과: {}", exec.getStatus());
    }

    /**
     * 매월 1일 03:00 — 직전 달의 월별 마감 실행
     */
    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Seoul")
    public void runMonthlyClosing() throws Exception {
        YearMonth target = YearMonth.now().minusMonths(1);
        log.info("[Scheduler] 월별 마감 트리거 — {}", target);

        Job job = jobRegistry.getJob("monthlyClosingJob");
        JobExecution exec = jobLauncher.run(job, new JobParametersBuilder()
                .addString("yearMonth", target.toString())
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters());

        log.info("[Scheduler] 월별 마감 결과: {}", exec.getStatus());
    }
}
