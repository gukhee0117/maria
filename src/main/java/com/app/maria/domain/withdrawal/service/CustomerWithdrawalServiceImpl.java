package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.CustomerWithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerWithdrawalServiceImpl implements CustomerWithdrawalService {

    private final AccountMapper accountMapper;
    private final WithdrawalService withdrawalService;
    private final GeneralAccountClient generalAccountClient;

    @Override
    @Transactional(readOnly = true)
    public List<GeneralAccountResponseDTO> getDestinationAccounts(Long customerId) {
        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        return generalAccountClient.findByCiHash(ciHash);
    }

    @Override
    public WithdrawalResultDTO withdraw(Long customerId, CustomerWithdrawalRequestDTO request) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));

        WithdrawalRequestDTO requestDTO =
                WithdrawalRequestDTO.builder()
                        .accountId(account.getAccountId())
                        .requestedAmount(request.getRequestedAmount())
                        .destinationGeneralAccountId(request.getDestinationGeneralAccountId())
                        .earlyWithdrawalAgreed(request.isEarlyWithdrawalAgreed())
                        .build();

        return withdrawalService.withdraw(requestDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalListResponseDTO> getWithdrawals(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        return withdrawalService.getWithdrawalsByAccountId(account.getAccountId());
    }

    @Override
    @Transactional(readOnly = true)
    public WithdrawalDetailResponseDTO getWithdrawal(Long customerId, Long withdrawalId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        return withdrawalService.getWithdrawalByIdAndAccountId(
                withdrawalId, account.getAccountId());
    }
}
