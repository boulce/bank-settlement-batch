package com.finance.settlement.batch.closing;

import com.finance.settlement.domain.MonthlyAccountSummary;
import com.finance.settlement.repository.MonthlyAccountSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Job 3: 월별 마감 배치
 *
 * 입력: daily_transaction_summaries (해당 월 범위)
 * 출력: monthly_account_summaries (계좌당 1행)
 *
 * 단계:
 *   1) closingCleanupStep (Tasklet)  — 같은 yearMonth의 기존 monthly summary 삭제
 *   2) closingStep (Chunk)           — JPQL GROUP BY로 일일 → 월간 집계
 *
 * 실행:
 *   ./gradlew bootRun --args='--job=monthlyClosingJob --yearMonth=2026-05'
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MonthlyClosingJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MonthlyClosingItemReader readerFactory;
    private final MonthlyClosingItemProcessor itemProcessor;
    private final MonthlyClosingItemWriter itemWriter;
    private final MonthlyAccountSummaryRepository repository;

    @Bean
    public Job monthlyClosingJob(Step closingCleanupStep, Step closingStep) {
        return new JobBuilder("monthlyClosingJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(closingCleanupStep)
                .next(closingStep)
                .listener(closingJobListener())
                .build();
    }

    @Bean
    public Step closingCleanupStep(Tasklet closingCleanupTasklet) {
        return new StepBuilder("closingCleanupStep", jobRepository)
                .tasklet(closingCleanupTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet closingCleanupTasklet(@Value("#{jobParameters['yearMonth']}") String yearMonth) {
        return (contribution, chunkContext) -> {
            long deleted = repository.deleteByYearMonthKey(yearMonth);
            log.info("이전 월별 summary 삭제: yearMonth={}, deleted={}건", yearMonth, deleted);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @JobScope
    public Step closingStep(@Value("#{jobParameters['yearMonth']}") String yearMonth) {
        YearMonth ym = YearMonth.parse(yearMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        return new StepBuilder("closingStep", jobRepository)
                .<MonthlyAggregateRow, MonthlyAccountSummary>chunk(1000, transactionManager)
                .reader(readerFactory.create(start, end))
                .processor(itemProcessor)
                .writer(itemWriter)
                .faultTolerant()
                .skipLimit(10)
                .skip(IllegalStateException.class)
                .listener(stepListener(yearMonth, start, end))
                .build();
    }

    @Bean
    public JobExecutionListener closingJobListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("=== 월별 마감 시작: yearMonth={} ===",
                        jobExecution.getJobParameters().getString("yearMonth"));
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                    log.info("=== 월별 마감 완료 ===");
                } else {
                    log.error("=== 월별 마감 실패: status={} ===", jobExecution.getStatus());
                }
            }
        };
    }

    private StepExecutionListener stepListener(String yearMonth, LocalDate start, LocalDate end) {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("월별 마감 대상: {} ({} ~ {})", yearMonth, start, end);
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                log.info("월별 마감 - 읽기: {}건, 쓰기: {}건, 스킵: {}건",
                        stepExecution.getReadCount(),
                        stepExecution.getWriteCount(),
                        stepExecution.getSkipCount());
                return stepExecution.getExitStatus();
            }
        };
    }
}
