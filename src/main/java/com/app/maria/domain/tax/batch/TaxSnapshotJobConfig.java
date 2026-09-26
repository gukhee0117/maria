package com.app.maria.domain.tax.batch;

import com.app.maria.domain.tax.dto.TaxSnapshotDTO;
import com.app.maria.domain.tax.dto.TaxSnapshotTargetDTO;
import com.app.maria.global.error.AppException;
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
    private static final int SKIP_LIMIT = 100;

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
            TaxSnapshotSkipListener skipListener) {
        return new StepBuilder("taxSnapshotStep", jobRepository)
                .<TaxSnapshotTargetDTO, TaxSnapshotDTO>chunk(CHUNK_SIZE, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                // AppException 발생 시 해당 계좌 건만 스킵하고 배치는 계속 진행.
                // 주의: 현재는 이 배치 스텝에서 AppException을 던지는 코드가 세액 도메인뿐이라 안전하지만,
                // 다른 도메인도 AppException을 쓰게 되면 세액과 무관한 예외까지 여기서 스킵될 수 있음.
                // 그때는 ErrorType으로 세액 관련 예외만 걸러내는 SkipPolicy로 교체할 것.
                .skip(AppException.class)
                .skipLimit(SKIP_LIMIT)
                .listener(skipListener)
                .build();
    }
}
