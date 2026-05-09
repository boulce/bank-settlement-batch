package com.finance.settlement.batch.aggregation;

import com.finance.settlement.domain.DailyTransactionSummary;
import com.finance.settlement.repository.DailyTransactionSummaryRepository;
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

/**
 * Job 1: 일별 거래 집계 배치
 *
 * 단계:
 *   1) cleanupStep (Tasklet)         — settlementDate 기존 summary 삭제 (멱등성 확보)
 *   2) aggregationStep (Chunk-1000)  — JPQL GROUP BY 집계 → DailyTransactionSummary 저장
 *
 * 실행:
 *   ./gradlew bootRun --args='--job=dailyTransactionAggregationJob --settlementDate=2026-04-30'
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DailyTransactionAggregationJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionAggregationItemReader itemReaderFactory;
    private final TransactionAggregationItemProcessor itemProcessor;
    private final TransactionAggregationItemWriter itemWriter;
    private final DailyTransactionSummaryRepository summaryRepository;

    @Bean
    public Job dailyTransactionAggregationJob(Step cleanupStep, Step aggregationStep) {
        return new JobBuilder("dailyTransactionAggregationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(cleanupStep)
                .next(aggregationStep)
                .listener(aggregationJobListener())
                .build();
    }

    @Bean
    public Step cleanupStep(Tasklet cleanupTasklet) {
        return new StepBuilder("cleanupStep", jobRepository)
                .tasklet(cleanupTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet cleanupTasklet(@Value("#{jobParameters['settlementDate']}") String settlementDateStr) {
        LocalDate settlementDate = LocalDate.parse(settlementDateStr);
        return (contribution, chunkContext) -> {
            long deleted = summaryRepository.deleteBySettlementDate(settlementDate);
            log.info("이전 summary 삭제: settlementDate={}, deleted={}건", settlementDate, deleted);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @JobScope
    public Step aggregationStep(@Value("#{jobParameters['settlementDate']}") String settlementDateStr) {
        LocalDate settlementDate = LocalDate.parse(settlementDateStr);
        return new StepBuilder("aggregationStep", jobRepository)
                .<AggregateRow, DailyTransactionSummary>chunk(1000, transactionManager)
                .reader(itemReaderFactory.create(settlementDate))
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
                log.info("=== 일별 거래 집계 시작: settlementDate={} ===",
                        jobExecution.getJobParameters().getString("settlementDate"));
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                    log.info("=== 일별 거래 집계 완료 ===");
                } else {
                    log.error("=== 일별 거래 집계 실패: status={} ===", jobExecution.getStatus());
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
                log.info("집계 완료 - 읽기: {}건, 쓰기: {}건, 필터: {}건, 스킵: {}건",
                        stepExecution.getReadCount(),
                        stepExecution.getWriteCount(),
                        stepExecution.getFilterCount(),
                        stepExecution.getSkipCount());
                return stepExecution.getExitStatus();
            }
        };
    }
}
