package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.withdrawal.dto.WithdrawalDTO;
import com.app.maria.domain.withdrawal.exception.InsufficientWithdrawalAmountException;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WithdrawalFailureService {

    private final WithdrawalMapper withdrawalMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInsufficientBalance(InsufficientWithdrawalAmountException exception) {
        WithdrawalDTO failedWithdrawal =
                WithdrawalDTO.builder()
                        .accountId(exception.getAccountId())
                        .requestedAmount(exception.getRequestedAmount())
                        .processedAt(exception.getFailedAt())
                        .destinationAccountNo(exception.getDestinationAccountNo())
                        .status(WithdrawalStatus.FAILED)
                        .destinationGeneralAccountId(exception.getDestinationGeneralAccountId())
                        .build();

        int insertedRows = withdrawalMapper.insertWithdrawal(failedWithdrawal);
        if (insertedRows != 1) {
            throw new AppException(
                    ErrorType.WITHDRAWAL_PROCESSING_FAILED, exception.getAccountId());
        }
    }
}
