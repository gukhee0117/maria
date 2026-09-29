package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.withdrawal.dto.WithdrawalHistoryDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WithdrawalQueryServiceImpl implements WithdrawalQueryService {
    private final WithdrawalMapper withdrawalMapper;

    @Override
    public List<WithdrawalListResponseDTO> getWithdrawals(WithdrawalStatus status) {
        return withdrawalMapper.selectWithdrawalHistories(status).stream()
                .map(WithdrawalListResponseDTO::from)
                .toList();
    }

    @Override
    public List<WithdrawalListResponseDTO> getWithdrawalsByAccountId(Long accountId) {
        return withdrawalMapper.selectWithdrawalHistoriesByAccountId(accountId).stream()
                .map(WithdrawalListResponseDTO::from)
                .toList();
    }

    @Override
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
}
