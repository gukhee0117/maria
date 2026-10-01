package com.app.maria.domain.settlement.component;

import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.exception.ExchangeRateExternalApiException;
import com.app.maria.domain.settlement.exception.InvalidSettlementException;
import com.app.maria.domain.settlement.exception.KrwExchangeNotFoundException;
import com.app.maria.domain.settlement.exception.SettlementAccountMismatchException;
import com.app.maria.domain.settlement.exception.SettlementAccountNotFoundException;
import com.app.maria.domain.settlement.exception.SettlementCalculationException;
import com.app.maria.domain.settlement.exception.SettlementStateConflictException;
import com.app.maria.domain.settlement.mapper.SettlementItemMapper;
import com.app.maria.domain.settlement.type.SettlementFailureCode;
import com.app.maria.domain.settlement.type.SettlementItemResult;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
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
            throw new SettlementStateConflictException("기록 대상 Item 미확인");
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
            throw new SettlementStateConflictException("정산 Item 실패 기록 실패");
        }
    }

    private SettlementFailureCode classify(Exception exception) {
        if (exception instanceof AppException appEx
                && appEx.getErrorType() == ErrorType.EXCHANGE_RATE_NOT_FOUND) {
            return SettlementFailureCode.EXCHANGE_RATE_NOT_FOUND;
        }
        if (exception instanceof ExchangeRateExternalApiException) {
            return SettlementFailureCode.EXTERNAL_API_ERROR;
        }
        if (exception instanceof KrwExchangeNotFoundException) {
            return SettlementFailureCode.EXCHANGE_NOT_FOUND;
        }
        if (exception instanceof SettlementAccountNotFoundException) {
            return SettlementFailureCode.ACCOUNT_NOT_FOUND;
        }
        if (exception instanceof SettlementAccountMismatchException) {
            return SettlementFailureCode.ACCOUNT_MISMATCH;
        }
        if (exception instanceof InvalidSettlementException) {
            return SettlementFailureCode.INVALID_ORDER_STATUS;
        }
        if (exception instanceof SettlementCalculationException) {
            return SettlementFailureCode.CALCULATION_ERROR;
        }
        if (exception instanceof SettlementStateConflictException) {
            return SettlementFailureCode.DB_STATE_CONFLICT;
        }
        return SettlementFailureCode.UNKNOWN_ERROR;
    }

    private String message(Exception exception) {
        String message = exception == null ? "알 수 없는 정산 오류" : exception.getMessage();
        return message == null || message.isBlank()
                ? "알 수 없는 정산 오류"
                : message.substring(0, Math.min(message.length(), 500));
    }
}
