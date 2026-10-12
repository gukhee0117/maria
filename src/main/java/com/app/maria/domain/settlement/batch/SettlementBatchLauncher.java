package com.app.maria.domain.settlement.batch;

import com.app.maria.domain.settlement.component.SettlementBatchStatusUpdater;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.global.error.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementBatchLauncher {

    private final JobLauncher jobLauncher;
    private final Job settlementJob;
    private final SettlementBatchStatusUpdater statusUpdater;

    @Async("settlementBatchTaskExecutor")
    public void launch(SettlementBatchDTO batch) {
        launch(batch, batch.getRunId());
    }

    private void launch(SettlementBatchDTO batch, String runId) {
        JobParameters parameters =
                new JobParametersBuilder()
                        .addLong("batchId", batch.getBatchId())
                        .addString("runId", runId)
                        .toJobParameters();
        try {
            jobLauncher.run(settlementJob, parameters);
        } catch (Exception e) {
            log.error("확정산 Batch 실행 실패: batchId={}", batch.getBatchId(), e);
            try {
                statusUpdater.markFailedIfRunning(batch.getBatchId(), AppException.detailOf(e));
            } catch (Exception statusException) {
                log.error("확정산 Batch 실패 상태 기록 실패: batchId={}", batch.getBatchId(), statusException);
            }
        }
    }
}
