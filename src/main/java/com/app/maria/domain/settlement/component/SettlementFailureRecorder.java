package com.app.maria.domain.settlement.component;

import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.type.SettlementFailureCode;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SettlementFailureRecorder {

    private final SettlementItemMapper settlementItemMapper;
    private final BusinessClockService systemClock;

    @Transactional(
            transactionManager = "transactionManager",
            propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long itemId, Exception exception) {
        if (itemId == null) {
            throw new AppException(ErrorType.SETTLEMENT_STATE_CONFLICT, "기록 대상 Item 미확인");
        }

        SettlementItemDTO item =
                SettlementItemDTO.builder()
                        .itemId(itemId)
                        .result(SettlementItemResult.FAILED)
                        .processedAt(systemClock.now())
                        .failureCode(classify(exception))
                        .failureMessage(message(exception))
                        .build();

        if (settlementItemMapper.updateItemResult(item) != 1) {
            throw new AppException(ErrorType.SETTLEMENT_STATE_CONFLICT, "정산 Item 실패 기록 실패");
        }
    }

    private SettlementFailureCode classify(Exception exception) {
        if (!(exception instanceof AppException appEx)) {
            return SettlementFailureCode.UNKNOWN_ERROR;
        }
        return switch (appEx.getErrorType()) {
            case EXCHANGE_RATE_NOT_FOUND -> SettlementFailureCode.EXCHANGE_RATE_NOT_FOUND;
            case EXCHANGE_RATE_API_FAILED -> SettlementFailureCode.EXTERNAL_API_ERROR;
            case KRW_EXCHANGE_NOT_FOUND -> SettlementFailureCode.EXCHANGE_NOT_FOUND;
            case ACCOUNT_NOT_FOUND -> SettlementFailureCode.ACCOUNT_NOT_FOUND;
            case SETTLEMENT_ACCOUNT_MISMATCH -> SettlementFailureCode.ACCOUNT_MISMATCH;
            case SETTLEMENT_INVALID_REQUEST -> SettlementFailureCode.INVALID_ORDER_STATUS;
            case SETTLEMENT_CALCULATION_FAILED -> SettlementFailureCode.CALCULATION_ERROR;
            case SETTLEMENT_STATE_CONFLICT -> SettlementFailureCode.DB_STATE_CONFLICT;
            default -> SettlementFailureCode.UNKNOWN_ERROR;
        };
    }

    private String message(Exception exception) {
        String base = exception == null ? null : exception.getMessage();
        if (exception instanceof AppException appEx && appEx.getErrorData() != null) {
            base = base + ": " + appEx.getErrorData();
        }
        return base == null || base.isBlank()
                ? "알 수 없는 정산 오류"
                : base.substring(0, Math.min(base.length(), 500));
    }
}
