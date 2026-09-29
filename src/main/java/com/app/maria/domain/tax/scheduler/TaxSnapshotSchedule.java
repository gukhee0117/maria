package com.app.maria.domain.tax.scheduler;

import com.app.maria.domain.tax.batch.TaxSnapshotJobLauncher;
import com.app.maria.global.clock.service.BusinessClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class TaxSnapshotSchedule {
    private final TaxSnapshotJobLauncher taxSnapshotJobLauncher;
    private final BusinessClockService clockService;

    @Scheduled(
            cron = "${custom.tax.snapshot-cron:0 0 2 * * *}",
            zone = "${custom.tax.zone:Asia/Seoul}")
    public void triggerDailySnapshot() {
        try {
            taxSnapshotJobLauncher.launch(clockService.now());
        } catch (Exception e) {
            log.error("세액 스냅샷 Batch 실행 실패.", e);
        }
    }
}
