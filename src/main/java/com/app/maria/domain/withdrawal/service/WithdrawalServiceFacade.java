package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.exception.InsufficientWithdrawalAmountException;
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
        } catch (InsufficientWithdrawalAmountException exception) {
            withdrawalFailureService.recordInsufficientBalance(exception);
            throw exception;
        }
    }

    @Override
    public WithdrawalResultDTO withdrawForClosure(WithdrawalRequestDTO requestDTO) {
        try {
            return withdrawalProcessor.withdrawForClosure(requestDTO);
        } catch (InsufficientWithdrawalAmountException exception) {
            withdrawalFailureService.recordInsufficientBalance(exception);
            throw exception;
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
