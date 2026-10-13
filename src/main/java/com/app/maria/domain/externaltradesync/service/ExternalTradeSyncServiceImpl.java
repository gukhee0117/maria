package com.app.maria.domain.externaltradesync.service;

import com.app.maria.domain.customer.dto.CustomerCiHashDTO;
import com.app.maria.domain.customer.mapper.CustomerMapper;
import com.app.maria.domain.externaltradesync.dto.ExternalTradeSyncCursorDTO;
import com.app.maria.domain.externaltradesync.dto.request.MydataTradeRequestDTO;
import com.app.maria.domain.externaltradesync.dto.response.ExternalTradeSyncResultDTO;
import com.app.maria.domain.externaltradesync.dto.response.MydataTradeResponseDTO;
import com.app.maria.domain.externaltradesync.mapper.ExternalTradeSyncCursorMapper;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailureDTO;
import com.app.maria.domain.targetproduct.mapper.TargetProductMapper;
import com.app.maria.domain.targetproduct.service.TargetProductService;
import com.app.maria.global.client.mydatatrade.MydataTradeClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalTradeSyncServiceImpl implements ExternalTradeSyncService {

    private final CustomerMapper customerMapper;
    private final ExternalTradeSyncCursorMapper cursorMapper;
    private final MydataTradeClient mydataTradeClient;
    private final TargetProductMapper targetProductMapper;
    private final TargetProductService targetProductService;
    private final BusinessClockService businessClockService;

    @Value("${custom.external-trade-sync.max-judge-failure-days:3}")
    private int maxJudgeFailureDays;

    private enum JudgeOutcome {
        NEW,
        SKIPPED,
        FAILED,
        PERMANENTLY_FAILED
    }

    private record JudgeResult(JudgeOutcome outcome, Long judgementId) {}

    private record CustomerSyncResult(
            List<Long> newJudgementIds, int skippedCount, int permanentlyFailedCount) {}

    @Override
    public ExternalTradeSyncResultDTO syncAll() {
        List<CustomerCiHashDTO> customers = customerMapper.selectActiveRiaCustomers();
        int failedCustomerCount = 0;
        int skippedJudgementCount = 0;
        int permanentlyFailedJudgementCount = 0;
        List<Long> newJudgementIds = new ArrayList<>();
        for (CustomerCiHashDTO customer : customers) {
            try {
                CustomerSyncResult result = syncCustomer(customer);
                newJudgementIds.addAll(result.newJudgementIds());
                skippedJudgementCount += result.skippedCount();
                permanentlyFailedJudgementCount += result.permanentlyFailedCount();
            } catch (Exception e) {
                failedCustomerCount++;
                log.warn("고객 동기화 실패, 다음 고객으로 진행합니다. customerId={}", customer.getCustomerId(), e);
            }
        }
        return ExternalTradeSyncResultDTO.builder()
                .customerCount(customers.size())
                .failedCustomerCount(failedCustomerCount)
                .newJudgementCount(newJudgementIds.size())
                .skippedJudgementCount(skippedJudgementCount)
                .permanentlyFailedJudgementCount(permanentlyFailedJudgementCount)
                .newJudgementIds(newJudgementIds)
                .build();
    }

    private CustomerSyncResult syncCustomer(CustomerCiHashDTO customer) {
        LocalDate fromDate =
                cursorMapper
                        .selectByCustomerId(customer.getCustomerId())
                        .map(ExternalTradeSyncCursorDTO::getLastSyncedTradeDate)
                        .orElse(null);

        MydataTradeRequestDTO request =
                MydataTradeRequestDTO.builder()
                        .ciHash(customer.getCiHash())
                        .fromDate(fromDate)
                        .build();

        List<MydataTradeResponseDTO> trades = mydataTradeClient.getTrades(request);
        LocalDate today = businessClockService.now().toLocalDate();
        trades =
                trades.stream()
                        .filter(trade -> !trade.getTradeDate().isAfter(today))
                        .filter(
                                trade -> {
                                    boolean match = customer.getCiHash().equals(trade.getCiHash());
                                    if (!match) {
                                        log.warn(
                                                "요청과 다른 ci_hash 응답, 스킵합니다. customerId={}, tradeId={}",
                                                customer.getCustomerId(),
                                                trade.getTradeId());
                                    }
                                    return match;
                                })
                        .sorted(Comparator.comparing(MydataTradeResponseDTO::getTradeDate))
                        .toList();

        LocalDate cursor = fromDate;
        boolean blocked = false;
        List<Long> newJudgementIds = new ArrayList<>();
        int skippedCount = 0;
        int permanentlyFailedCount = 0;
        for (MydataTradeResponseDTO trade : trades) {
            JudgeResult result = judgeTrade(trade);
            switch (result.outcome()) {
                case NEW -> newJudgementIds.add(result.judgementId());
                case SKIPPED -> skippedCount++;
                case PERMANENTLY_FAILED -> permanentlyFailedCount++;
                case FAILED -> blocked = true;
            }
            if (!blocked) {
                cursor = trade.getTradeDate();
            }
        }

        if (cursor != null) {
            cursorMapper.upsertCursor(
                    ExternalTradeSyncCursorDTO.builder()
                            .customerId(customer.getCustomerId())
                            .lastSyncedTradeDate(cursor)
                            .build());
        }
        return new CustomerSyncResult(newJudgementIds, skippedCount, permanentlyFailedCount);
    }

    private JudgeResult judgeTrade(MydataTradeResponseDTO trade) {
        Optional<TargetProductJudgementFailureDTO> existingFailure =
                targetProductMapper.selectFailureByMydataTradeId(trade.getTradeId());
        if (existingFailure.map(this::isPermanentlyFailed).orElse(false)) {
            return new JudgeResult(JudgeOutcome.PERMANENTLY_FAILED, null);
        }
        try {
            if (targetProductMapper.existsByMydataTradeId(trade.getTradeId())) {
                return new JudgeResult(JudgeOutcome.SKIPPED, null);
            }
            TargetProductJudgementDTO judged = targetProductService.judge(trade);
            if (existingFailure.isPresent()) {
                targetProductMapper.deleteFailureByMydataTradeId(trade.getTradeId());
            }
            return new JudgeResult(JudgeOutcome.NEW, judged.getJudgementId());
        } catch (Exception e) {
            log.warn("거래 판정 실패. tradeId={}", trade.getTradeId(), e);
            recordFailure(trade, e);
            return new JudgeResult(JudgeOutcome.FAILED, null);
        }
    }

    private boolean isPermanentlyFailed(TargetProductJudgementFailureDTO failure) {
        long daysSinceFirstFailure =
                Duration.between(failure.getFirstFailedAt(), businessClockService.now()).toDays();
        return daysSinceFirstFailure >= maxJudgeFailureDays;
    }

    private void recordFailure(MydataTradeResponseDTO trade, Exception e) {
        LocalDateTime now = businessClockService.now();
        String detail =
                e instanceof AppException appException
                        ? e.getMessage() + " data=" + appException.getErrorData()
                        : e.getMessage();
        targetProductMapper.upsertFailure(
                TargetProductJudgementFailureDTO.builder()
                        .mydataTradeId(trade.getTradeId())
                        .ciHash(trade.getCiHash())
                        .tradeDate(trade.getTradeDate())
                        .lastError(truncate(detail, 500))
                        .firstFailedAt(now)
                        .lastFailedAt(now)
                        .build());
    }

    private static String truncate(String message, int maxLength) {
        if (message == null || message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength);
    }
}
