package com.app.maria.domain.settlement.component;

import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.event.SettlementItemCompletedEvent;
import com.app.maria.domain.settlement.mapper.KrwExchangeMapper;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.domain.settlement.type.SettlementStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 확정산 Item 한 건을 독립 트랜잭션으로 처리 외부 환율 조회는 이 컴포넌트에 진입하기 전에 끝내야 한다. */
@Component
@RequiredArgsConstructor
public class SettlementTransactionExecutor {

    private final KrwExchangeMapper krwExchangeMapper;
    private final SettlementItemMapper settlementItemMapper;
    private final SettlementCalculator settlementCalculator;
    private final BusinessClockService businessClockService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(
            transactionManager = "transactionManager",
            propagation = Propagation.REQUIRES_NEW)
    public void execute(SettlementJoinDTO target, BigDecimal finalRate) {
        validateTarget(target);

        KrwExchangeDTO exchange =
                krwExchangeMapper
                        .selectExchangeByIdForUpdate(target.getExchangeId())
                        .orElseThrow(
                                () -> {
                                    return new AppException(
                                            ErrorType.KRW_EXCHANGE_NOT_FOUND, "환전 정보 조회 실패");
                                });

        if (exchange.getSettlementStatus() == SettlementStatus.FINALIZED) {
            LocalDateTime completedAt = businessClockService.now();
            markItemSuccess(target.getItemId(), completedAt);
            publishItemCompleted(target, exchange.getAccountId(), completedAt);
            return;
        }
        if (exchange.getSettlementStatus() != SettlementStatus.PROVISIONAL) {
            throw new AppException(ErrorType.SETTLEMENT_STATE_CONFLICT, "확정산 대상 상태가 올바르지 않습니다.");
        }
        if (target.getSellOrderStatus() == null
                || !SellOrderStatus.EXECUTED.equals(target.getSellOrderStatus())) {
            throw new AppException(ErrorType.SETTLEMENT_INVALID_REQUEST, "체결된 매도 주문만 확정산 가능");
        }
        if (!exchange.getAccountId().equals(target.getAccountId())) {
            throw new AppException(
                    ErrorType.SETTLEMENT_ACCOUNT_MISMATCH, "환전과 정산 대상 계좌가 일치하지 않습니다.");
        }

        // 모든 정산 경로에서 exchange -> account 순서로 잠금을 획득한다.
        krwExchangeMapper
                .selectAccountAmountForUpdate(exchange.getAccountId())
                .orElseThrow(
                        () ->
                                new AppException(
                                        ErrorType.ACCOUNT_NOT_FOUND, "정산 대상 계좌를 찾을 수 없습니다."));

        BigDecimal finalAmount =
                settlementCalculator.calculateFinalAmount(
                        exchange.getProvisionalAmount(), target.getSettlementFxRate(), finalRate);
        LocalDateTime settledAt = businessClockService.now();

        exchange.setFinalRate(finalRate);
        exchange.setFinalAmount(finalAmount);
        exchange.setSettlementStatus(SettlementStatus.FINALIZED);

        requireOneRow(krwExchangeMapper.finalizeExchange(exchange), "환전 확정 실패");

        requireOneRow(krwExchangeMapper.replaceAccountAmount(exchange), "계좌 잔고 반영 실패");

        requireOneRow(krwExchangeMapper.insertLeftAmount(exchange), "잔여 금액 생성 실패");

        markItemSuccess(target.getItemId(), settledAt);

        publishItemCompleted(target, exchange.getAccountId(), settledAt);
    }

    private void publishItemCompleted(
            SettlementJoinDTO target, Long accountId, LocalDateTime completedAt) {
        eventPublisher.publishEvent(
                new SettlementItemCompletedEvent(target.getBatchId(), accountId, completedAt));
    }

    private void validateTarget(SettlementJoinDTO target) {
        if (target == null
                || target.getItemId() == null
                || target.getExchangeId() == null
                || target.getAccountId() == null) {
            throw new AppException(ErrorType.SETTLEMENT_INVALID_REQUEST, "확정산 대상 정보가 올바르지 않습니다.");
        }
    }

    private void markItemSuccess(Long itemId, LocalDateTime processedAt) {
        SettlementItemDTO item =
                SettlementItemDTO.builder()
                        .itemId(itemId)
                        .result(SettlementItemResult.SUCCESS)
                        .processedAt(processedAt)
                        .build();
        requireOneRow(settlementItemMapper.updateItemResult(item), "정산 Item 성공 기록 실패");
    }

    private void requireOneRow(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new AppException(ErrorType.SETTLEMENT_STATE_CONFLICT, message);
        }
    }
}
