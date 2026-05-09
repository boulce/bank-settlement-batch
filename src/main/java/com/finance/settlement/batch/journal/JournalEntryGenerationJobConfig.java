package com.finance.settlement.batch.journal;

import com.finance.settlement.domain.JournalEntry;
import com.finance.settlement.domain.Transaction;
import com.finance.settlement.repository.JournalEntryRepository;
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
import java.util.List;

/**
 * Job 2: 분개 생성 배치
 *
 * 단계:
 *   1) journalCleanupStep (Tasklet)  — entryDate 기존 journal_entries 삭제
 *   2) journalGenerationStep (Chunk) — Transaction 1 → JournalEntry 2 (debit/credit) 매핑 후 저장
 *
 * 실행:
 *   ./gradlew bootRun --args='--job=journalEntryGenerationJob --entryDate=2026-05-05'
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class JournalEntryGenerationJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final JournalEntryItemReader readerFactory;
    private final JournalEntryItemProcessor itemProcessor;
    private final JournalEntryItemWriter itemWriter;
    private final JournalEntryRepository journalEntryRepository;

    @Bean
    public Job journalEntryGenerationJob(Step journalCleanupStep, Step journalGenerationStep) {
        return new JobBuilder("journalEntryGenerationJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(journalCleanupStep)
                .next(journalGenerationStep)
                .listener(journalJobListener())
                .build();
    }

    @Bean
    public Step journalCleanupStep(Tasklet journalCleanupTasklet) {
        return new StepBuilder("journalCleanupStep", jobRepository)
                .tasklet(journalCleanupTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet journalCleanupTasklet(@Value("#{jobParameters['entryDate']}") String entryDateStr) {
        LocalDate entryDate = LocalDate.parse(entryDateStr);
        return (contribution, chunkContext) -> {
            long deleted = journalEntryRepository.deleteByEntryDate(entryDate);
            log.info("이전 분개 삭제: entryDate={}, deleted={}건", entryDate, deleted);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @JobScope
    public Step journalGenerationStep(@Value("#{jobParameters['entryDate']}") String entryDateStr) {
        LocalDate entryDate = LocalDate.parse(entryDateStr);
        return new StepBuilder("journalGenerationStep", jobRepository)
                .<Transaction, List<JournalEntry>>chunk(1000, transactionManager)
                .reader(readerFactory.create(entryDate))
                .processor(itemProcessor)
                .writer(itemWriter)
                .faultTolerant()
                .skipLimit(10)
                .skip(IllegalStateException.class)
                .listener(stepListener(entryDate))
                .build();
    }

    @Bean
    public JobExecutionListener journalJobListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("=== 분개 생성 시작: entryDate={} ===",
                        jobExecution.getJobParameters().getString("entryDate"));
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
                    log.info("=== 분개 생성 완료 ===");
                } else {
                    log.error("=== 분개 생성 실패: status={} ===", jobExecution.getStatus());
                }
            }
        };
    }

    private StepExecutionListener stepListener(LocalDate entryDate) {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("분개 대상 날짜: {}", entryDate);
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                log.info("분개 생성 - 읽기: {}건, 쓰기: {}건, 스킵: {}건",
                        stepExecution.getReadCount(),
                        stepExecution.getWriteCount(),
                        stepExecution.getSkipCount());
                return stepExecution.getExitStatus();
            }
        };
    }
}
