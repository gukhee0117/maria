package com.app.maria.domain.withdrawal.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.CustomerWithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.CustomerWithdrawalAvailabilityResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.CustomerWithdrawalAvailabilityResponseDTO.LeftAmountItem;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerWithdrawalServiceImpl implements CustomerWithdrawalService {

    private final AccountMapper accountMapper;
    private final WithdrawalService withdrawalService;
    private final WithdrawalMapper withdrawalMapper;
    private final BusinessClockService businessClockService;
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
    @Transactional(readOnly = true)
    public CustomerWithdrawalAvailabilityResponseDTO getAvailability(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));

        LocalDateTime now = businessClockService.now();
        List<LeftAmountDTO> leftAmountDTOs =
                withdrawalMapper.selectAvailableLeftAmountsByAccountId(account.getAccountId());

        BigDecimal provisionalTotal =
                withdrawalMapper.selectProvisionalAmountByAccountId(account.getAccountId());
        BigDecimal cashAmount = account.getAmount();
        BigDecimal requestableAmount = cashAmount.subtract(provisionalTotal).max(BigDecimal.ZERO);

        List<LeftAmountItem> leftAmounts =
                leftAmountDTOs.stream()
                        .map(la -> new LeftAmountItem(la.getFinalAt(), la.getCurAmount(), now))
                        .toList();

        BigDecimal totalPrincipal =
                leftAmounts.stream()
                        .map(LeftAmountItem::getCurAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal earnings = requestableAmount.subtract(totalPrincipal).max(BigDecimal.ZERO);

        BigDecimal maturedPrincipal =
                leftAmounts.stream()
                        .filter(LeftAmountItem::isMatured)
                        .map(LeftAmountItem::getCurAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal immaturePrincipalAmount = totalPrincipal.subtract(maturedPrincipal);

        BigDecimal withdrawableWithoutPenalty =
                requestableAmount.min(earnings.add(maturedPrincipal));

        return new CustomerWithdrawalAvailabilityResponseDTO(
                cashAmount,
                provisionalTotal,
                requestableAmount,
                earnings,
                withdrawableWithoutPenalty,
                immaturePrincipalAmount,
                now,
                leftAmounts);
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
