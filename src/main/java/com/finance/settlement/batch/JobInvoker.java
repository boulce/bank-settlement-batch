package com.finance.settlement.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobInvoker implements ApplicationRunner {

    private static final String JOB_ARG = "job";

    private final JobLauncher jobLauncher;
    private final JobRegistry jobRegistry;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption(JOB_ARG)) {
            return;
        }

        String jobName = args.getOptionValues(JOB_ARG).get(0);
        Job job = jobRegistry.getJob(jobName);

        JobParametersBuilder params = new JobParametersBuilder();
        for (String optionName : args.getOptionNames()) {
            if (JOB_ARG.equals(optionName)) continue;
            params.addString(optionName, args.getOptionValues(optionName).get(0));
        }
        params.addLong("run.id", System.currentTimeMillis());

        JobExecution execution = jobLauncher.run(job, params.toJobParameters());
        log.info("Job [{}] finished with status {}", jobName, execution.getStatus());

        // 배치 모드는 잡 실행 후 즉시 종료. status에 따라 exit code만 다르게.
        System.exit(execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1);
    }
}
