package com.app.maria.domain.settlement.batch;

import com.app.maria.domain.settlement.component.SettlementBatchStatusUpdater;
import com.app.maria.global.error.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SettlementJobListener implements JobExecutionListener {

    private final SettlementBatchStatusUpdater statusUpdater;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() == org.springframework.batch.core.BatchStatus.COMPLETED) {
            return;
        }

        Long batchId = jobExecution.getJobParameters().getLong("batchId");
        if (batchId == null) {
            return;
        }

        String message =
                jobExecution.getAllFailureExceptions().isEmpty()
                        ? "확정산 Job 실행 실패"
                        : AppException.detailOf(jobExecution.getAllFailureExceptions().get(0));
        statusUpdater.markFailedIfRunning(batchId, message);
    }
}
