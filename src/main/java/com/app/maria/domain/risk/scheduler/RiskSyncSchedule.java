package com.app.maria.domain.risk.scheduler;

import com.app.maria.domain.risk.service.RiskSyncCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "custom.risk.sync-enabled", havingValue = "true")
public class RiskSyncSchedule {
    private final RiskSyncCoordinator coordinator;

    @Scheduled(fixedDelayString = "${custom.risk.tick-millis:30000}")
    public void tick() {
        try {
            coordinator.tick();
        } catch (RuntimeException e) {
            // Payloads/URLs and raw provider errors are deliberately not logged.
            log.warn("RISK_SYNC_TICK_FAILED type={}", e.getClass().getSimpleName());
        }
    }
}
