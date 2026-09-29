package com.app.maria.domain.tax.batch;

import com.app.maria.domain.tax.dto.TaxSnapshotDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotTargetDTO;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TaxSnapshotJobConfig {
    private static final int CHUNK_SIZE = 200;

    @Bean
    public Job taxSnapshotJob(JobRepository jobRepository, Step taxSnapshotStep) {
        return new JobBuilder("taxSnapshotJob", jobRepository).start(taxSnapshotStep).build();
    }

    @Bean
    public Step taxSnapshotStep(
            JobRepository jobRepository,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            TaxSnapshotTargetReader reader,
            TaxSnapshotWriter writer,
            TaxSnapshotProcessor processor,
            TaxSnapshotSkipListener skipListener,
            TaxSnapshotSkipPolicy skipPolicy) {
        return new StepBuilder("taxSnapshotStep", jobRepository)
                .<TaxSnapshotTargetDTO, TaxSnapshotDTO>chunk(CHUNK_SIZE, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skipPolicy(skipPolicy)
                .listener(skipListener)
                .build();
    }
}
