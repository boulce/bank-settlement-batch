package com.finance.settlement.batch.aggregation;

import com.finance.settlement.domain.DailyTransactionSummary;
import com.finance.settlement.domain.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;

/**
 * Job 1: 일별 거래 집계 배치
 *
 * 실행:
 *   ./gradlew bootRun --args='--spring.batch.job.name=dailyTransactionAggregationJob --settlementDate=2026-05-03'
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DailyTransactionAggregationJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionAggregationItemReader itemReader;
    private final TransactionAggregationItemProcessor itemProcessor;
    private final TransactionAggregationItemWriter itemWriter;

    @Bean
    public Job dailyTransactionAggregationJob() {
        return new JobBuilder("dailyTransactionAggregationJob", jobRepository)
                .start(aggregationStep(null))
                .listener(aggregationJobListener())
                .build();
    }

    @Bean
    @StepScope
    public Step aggregationStep(
            @Value("#{jobParameters['settlementDate']}") String settlementDateStr) {

        LocalDate settlementDate = settlementDateStr != null
                ? LocalDate.parse(settlementDateStr)
                : LocalDate.now().minusDays(1);

        itemProcessor.setCurrentDate(settlementDate);

        return new StepBuilder("aggregationStep", jobRepository)
                .<Transaction, DailyTransactionSummary>chunk(1000, transactionManager)
                .reader(itemReader.create(settlementDate))
                .processor(itemProcessor)
                .writer(itemWriter)
                .faultTolerant()
                .skipLimit(10)
                .skip(IllegalStateException.class)
                .listener(aggregationStepListener(settlementDate))
                .build();
    }

    @Bean
    public JobExecutionListener aggregationJobListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("=== 일별 거래 집계 시작: {} ===",
                        jobExecution.getJobParameters().getString("settlementDate"));
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                    log.info("=== 일별 거래 집계 완료 ===");
                } else {
                    log.error("=== 일별 거래 집계 실패: {} ===", jobExecution.getStatus());
                }
            }
        };
    }

    private StepExecutionListener aggregationStepListener(LocalDate settlementDate) {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("집계 대상: {}", settlementDate);
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                log.info("집계 완료 - 읽기: {}건, 쓰기: {}건, 스킵: {}건",
                        stepExecution.getReadCount(),
                        stepExecution.getWriteCount(),
                        stepExecution.getSkipCount());
                return stepExecution.getExitStatus();
            }
        };
    }
}
