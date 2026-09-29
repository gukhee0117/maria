package com.app.maria.domain.tax.batch;

import com.app.maria.domain.tax.dto.TaxBatchHistoryDTO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaxSnapshotBatchHistoryReader {
    private static final String JOB_NAME = "taxSnapshotJob";
    private static final int MAX_INSTANCES = 20;

    private final JobExplorer jobExplorer;

    public List<TaxBatchHistoryDTO> findRecent() {
        List<JobInstance> instances = jobExplorer.getJobInstances(JOB_NAME, 0, MAX_INSTANCES);
        List<TaxBatchHistoryDTO> history = new ArrayList<>();
        for (JobInstance instance : instances) {
            for (JobExecution execution : jobExplorer.getJobExecutions(instance)) {
                history.add(toDTO(execution));
            }
        }
        history.sort(
                Comparator.comparing(
                        TaxBatchHistoryDTO::getStartTime,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return history;
    }

    private TaxBatchHistoryDTO toDTO(JobExecution execution) {
        long readCount = 0;
        long writeCount = 0;
        long skipCount = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            readCount += step.getReadCount();
            writeCount += step.getWriteCount();
            skipCount += step.getSkipCount();
        }

        return TaxBatchHistoryDTO.builder()
                .jobExecutionId(execution.getId())
                .runId(execution.getJobParameters().getString("runId"))
                .startTime(execution.getStartTime())
                .endTime(execution.getEndTime())
                .status(execution.getStatus().name())
                .exitStatus(execution.getExitStatus().getExitCode())
                .readCount(readCount)
                .writeCount(writeCount)
                .skipCount(skipCount)
                .build();
    }
}
