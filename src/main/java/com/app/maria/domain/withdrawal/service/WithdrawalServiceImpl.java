package com.app.maria.domain.withdrawal.service;

import static com.app.maria.global.client.generalaccount.type.GeneralAccountStatus.ACTIVE;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalFailureContext;
import com.app.maria.domain.withdrawal.dto.WithdrawalHistoryDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalDetailResponseDTO;
import com.app.maria.domain.withdrawal.dto.response.WithdrawalListResponseDTO;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalServiceImpl implements WithdrawalService {

    private final WithdrawalProcessor withdrawalProcessor;
    private final WithdrawalFailureService withdrawalFailureService;
    private final WithdrawalMapper withdrawalMapper;
    private final BusinessClockService businessClockService;
    private final AccountMapper accountMapper;
    private final GeneralAccountClient generalAccountClient;

    @Override
    public WithdrawalResultDTO withdraw(WithdrawalRequestDTO requestDTO) {
        GeneralAccountResponseDTO destination = verifyDestinationAccount(requestDTO);
        // 목적계좌 검증을 통과한 시점부터 인출 시도로 간주한다.
        // Processor 안에서 발생하는 모든 예외(잔액 부족·가환전 차단·DB 오류 등)를 기록 대상으로 삼는다.
        try {
            return withdrawalProcessor.withdraw(requestDTO, destination);
        } catch (Exception e) {
            try {
                recordFailed(requestDTO, destination);
            } catch (Exception re) {
                log.warn("인출 실패 이력 저장 중 오류 — accountId={}", requestDTO.getAccountId(), re);
            }
            throw e;
        }
    }

    @Override
    public WithdrawalResultDTO withdrawForClosure(WithdrawalRequestDTO requestDTO) {
        GeneralAccountResponseDTO destination = verifyDestinationAccount(requestDTO);
        // 실패 기록은 AccountClosureApprovalOrchestrator가 트랜잭션 롤백 후 담당한다.
        // 컨텍스트 기록은 WithdrawalProcessor 안에서 상태 검사 통과 후 수행된다.
        return withdrawalProcessor.withdrawForClosure(requestDTO, destination);
    }

    private GeneralAccountResponseDTO verifyDestinationAccount(WithdrawalRequestDTO requestDTO) {
        Long accountId = requestDTO.getAccountId();
        AccountDTO account =
                accountMapper
                        .selectByAccountId(accountId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, accountId));
        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(account.getCustomerId())
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND,
                                                account.getCustomerId()));
        GeneralAccountResponseDTO destination =
                generalAccountClient.verifyGeneralAccount(
                        GeneralAccountRequestDTO.builder()
                                .ciHash(ciHash)
                                .generalAccountId(requestDTO.getDestinationGeneralAccountId())
                                .build());
        if (destination.getStatus() != ACTIVE) {
            throw new AppException(
                    ErrorType.WITHDRAWAL_DESTINATION_ACCOUNT_INACTIVE,
                    requestDTO.getDestinationGeneralAccountId());
        }
        return destination;
    }

    private void recordFailed(
            WithdrawalRequestDTO requestDTO, GeneralAccountResponseDTO destination) {
        withdrawalFailureService.record(
                new WithdrawalFailureContext(
                        requestDTO.getAccountId(),
                        requestDTO.getRequestedAmount(),
                        businessClockService.now(),
                        destination.getAccountNo(),
                        requestDTO.getDestinationGeneralAccountId()));
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
    public WithdrawalDetailResponseDTO getWithdrawalByIdAndAccountId(
            Long withdrawalId, Long accountId) {
        WithdrawalHistoryDTO withdrawal =
                withdrawalMapper
                        .selectWithdrawalHistoryByIdAndAccountId(withdrawalId, accountId)
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
}
