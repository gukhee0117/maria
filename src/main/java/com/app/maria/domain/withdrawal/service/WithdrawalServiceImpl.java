package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.dto.WithdrawalHistoryDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WithdrawalServiceImpl implements WithdrawalService {

    private final WithdrawalProcessor withdrawalProcessor;
    private final WithdrawalFailureService withdrawalFailureService;
    private final WithdrawalMapper withdrawalMapper;
    private final BusinessClockService businessClockService;

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

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalListResponseDTO> getWithdrawals(WithdrawalStatus status) {
        return withdrawalMapper.selectWithdrawalHistories(status).stream()
                .map(WithdrawalListResponseDTO::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalListResponseDTO> getWithdrawalsByAccountId(Long accountId) {
        return withdrawalMapper.selectWithdrawalHistoriesByAccountId(accountId).stream()
                .map(WithdrawalListResponseDTO::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WithdrawalDetailResponseDTO getWithdrawal(Long withdrawalId) {
        WithdrawalHistoryDTO withdrawal =
                withdrawalMapper
                        .selectWithdrawalHistoryById(withdrawalId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.WITHDRAWAL_NOT_FOUND, withdrawalId));

        return WithdrawalDetailResponseDTO.from(
                withdrawal, withdrawalMapper.selectAllocationHistoriesByWithdrawalId(withdrawalId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasImmaturePrincipal(Long accountId) {
        return getImmaturePrincipalAmount(accountId).compareTo(BigDecimal.ZERO) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getImmaturePrincipalAmount(Long accountId) {
        List<LeftAmountDTO> leftAmounts =
                withdrawalMapper.selectAvailableLeftAmountsByAccountId(accountId);
        LocalDateTime currentDatetime = businessClockService.now();

        return leftAmounts.stream()
                .filter(leftAmount -> leftAmount.getFinalAt().plusYears(1).isAfter(currentDatetime))
                .map(LeftAmountDTO::getCurAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getImmatureAllocatedAmount(Long withdrawalId) {
        return withdrawalMapper.selectImmatureAllocatedAmountByWithdrawalId(withdrawalId);
    }

    private void recordInsufficientBalance(AppException exception) {
        if (exception.getErrorType() == ErrorType.INSUFFICIENT_WITHDRAWAL_AMOUNT
                && exception.getErrorData() instanceof WithdrawalFailureContext context) {
            withdrawalFailureService.recordInsufficientBalance(context);
        }
    }
}
