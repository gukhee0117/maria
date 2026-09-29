package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WithdrawalServiceFacade implements WithdrawalService {

    private final WithdrawalProcessor withdrawalProcessor;
    private final WithdrawalFailureService withdrawalFailureService;

    @Override
    public WithdrawalResultDTO withdraw(WithdrawalRequestDTO requestDTO) {
        try {
            return withdrawalProcessor.withdraw(requestDTO);
        } catch (AppException exception) {
            recordInsufficientBalance(exception);
            throw exception;
        }
    }

    @Override
    public WithdrawalResultDTO withdrawForClosure(WithdrawalRequestDTO requestDTO) {
        try {
            return withdrawalProcessor.withdrawForClosure(requestDTO);
        } catch (AppException exception) {
            recordInsufficientBalance(exception);
            throw exception;
        }
    }

    private void recordInsufficientBalance(AppException exception) {
        if (exception.getErrorType() == ErrorType.INSUFFICIENT_WITHDRAWAL_AMOUNT
                && exception.getErrorData() instanceof WithdrawalFailureContext context) {
            withdrawalFailureService.recordInsufficientBalance(context);
        }
    }

    @Override
    public boolean hasImmaturePrincipal(Long accountId) {
        return withdrawalProcessor.hasImmaturePrincipal(accountId);
    }

    @Override
    public BigDecimal getImmaturePrincipalAmount(Long accountId) {
        return withdrawalProcessor.getImmaturePrincipalAmount(accountId);
    }

    @Override
    public BigDecimal getImmatureAllocatedAmount(Long withdrawalId) {
        return withdrawalProcessor.getImmatureAllocatedAmount(withdrawalId);
    }
}
