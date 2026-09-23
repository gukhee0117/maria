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
                // ponytail: 지금은 AppException이면 무조건 이 계좌 건만 건너뛰고 배치는 계속 돎.
                // 지금은 이 배치 스텝에서 AppException을 던지는 게 세액 도메인 코드뿐이라 문제없음.
                // 나중에 다른 도메인도 AppException을 쓰기 시작하면, 이 배치랑 상관없는 이유로 터진
                // 예외까지 여기서 같이 건너뛰어버릴 수 있음 — 그때는 "세액 관련 ErrorType일 때만
                // skip" 하도록 SkipPolicy를 직접 만들어서 좁혀야 함.
                .skip(AppException.class)
                .skipLimit(SKIP_LIMIT)
                .listener(skipListener)
                .build();
    }
}
