package com.app.maria.domain.withdrawal.service;

import static com.app.maria.global.client.generalaccount.type.GeneralAccountStatus.ACTIVE;

import com.app.maria.domain.account.dto.AccountBenefitLogDTO;
import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.exception.AccountException;
import com.app.maria.domain.account.exception.AccountNotFoundException;
import com.app.maria.domain.account.mapper.AccountBenefitLogMapper;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.withdrawal.dto.LeftAmountDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalAllocationDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalDTO;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.exception.EarlyWithdrawalConsentRequiredException;
import com.app.maria.domain.withdrawal.exception.InsufficientWithdrawalAmountException;
import com.app.maria.domain.withdrawal.exception.WithdrawalNotAllowedException;
import com.app.maria.domain.withdrawal.exception.WithdrawalProcessingException;
import com.app.maria.domain.withdrawal.mapper.WithdrawalMapper;
import com.app.maria.domain.withdrawal.type.WithdrawalStatus;
import com.app.maria.domain.withdrawal.type.WithdrawalType;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WithdrawalProcessor {

    private final BusinessClockService businessClockService;
    private final AccountMapper accountMapper;
    private final WithdrawalMapper withdrawalMapper;
    private final AccountBenefitLogMapper accountBenefitLogMapper;
    private final GeneralAccountClient generalAccountClient;

    @Transactional
    public WithdrawalResultDTO withdraw(WithdrawalRequestDTO requestDTO) {
        return processWithdrawal(requestDTO, Status.OPENED);
    }

    private WithdrawalResultDTO processWithdrawal(
            WithdrawalRequestDTO requestDTO, Status allowedStatus) {
        Long accountId = requestDTO.getAccountId();
        BigDecimal requestedAmount = requestDTO.getRequestedAmount();

        if (requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new WithdrawalNotAllowedException("인출 요청금액은 0보다 커야 합니다.");
        }

        AccountDTO accountBeforeLock =
                accountMapper
                        .selectByAccountId(accountId)
                        .orElseThrow(() -> new AccountNotFoundException("인출 대상 계좌가 존재하지 않습니다."));
        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(accountBeforeLock.getCustomerId())
                        .orElseThrow(
                                () -> new AccountNotFoundException("인출 계좌의 고객 식별정보를 찾을 수 없습니다."));

        GeneralAccountRequestDTO generalAccountRequest =
                GeneralAccountRequestDTO.builder()
                        .ciHash(ciHash)
                        .generalAccountId(requestDTO.getDestinationGeneralAccountId())
                        .build();
        GeneralAccountResponseDTO destinationGeneralAccount =
                generalAccountClient.verifyGeneralAccount(generalAccountRequest);
        if (destinationGeneralAccount.getStatus() != ACTIVE) {
            throw new WithdrawalNotAllowedException("활성 상태의 일반계좌로만 인출할 수 있습니다.");
        }

        AccountDTO account =
                accountMapper
                        .selectByAccountIdForUpdate(accountId)
                        .orElseThrow(() -> new AccountNotFoundException("인출 대상 계좌가 존재하지 않습니다."));

        if (account.getStatus() != allowedStatus) {
            throw new WithdrawalNotAllowedException("현재 계좌 상태에서는 인출할 수 없습니다.");
        }

        LocalDateTime currentDatetime = businessClockService.now();

        if (account.getAmount().compareTo(requestedAmount) < 0) {
            throw new InsufficientWithdrawalAmountException(
                    "계좌 잔액보다 많은 금액을 인출할 수 없습니다.",
                    accountId,
                    requestedAmount,
                    currentDatetime,
                    destinationGeneralAccount.getAccountNo(),
                    requestDTO.getDestinationGeneralAccountId());
        }

        List<LeftAmountDTO> leftAmounts =
                withdrawalMapper.selectAvailableLeftAmountsByAccountId(accountId);

        // 확정된 납입 원금의 잔액 합계와 자유롭게 인출할 수 있는 수익금을 계산한다.
        BigDecimal totalPrincipal =
                leftAmounts.stream()
                        .map(LeftAmountDTO::getCurAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal earnings = account.getAmount().subtract(totalPrincipal).max(BigDecimal.ZERO);
        BigDecimal earningsAllocation = requestedAmount.min(earnings);
        BigDecimal remainingRequest = requestedAmount.subtract(earningsAllocation);

        // finalAt으로부터 1년이 지난 납입 원금만 경과 원금으로 분류한다.
        List<LeftAmountDTO> maturedLeftAmounts =
                leftAmounts.stream()
                        .filter(
                                leftAmount ->
                                        !leftAmount
                                                .getFinalAt()
                                                .plusYears(1)
                                                .isAfter(currentDatetime))
                        .toList();

        List<WithdrawalAllocationDTO> allocations = new ArrayList<>();

        // 요청액 중 수익금으로 충당할 수 있는 금액을 가장 먼저 배분한다.
        if (earningsAllocation.compareTo(BigDecimal.ZERO) > 0) {
            allocations.add(
                    WithdrawalAllocationDTO.builder()
                            .leftAmountId(null)
                            .allocatedAmount(earningsAllocation)
                            .withdrawalAt(currentDatetime)
                            .type(WithdrawalType.EARNINGS_ONLY)
                            .build());
        }

        // 수익금 배분 후 남은 요청액을 오래된 경과 원금부터 FIFO로 배분한다.
        List<WithdrawalAllocationDTO> maturedAllocations =
                allocateMaturedPrincipalFifo(remainingRequest, maturedLeftAmounts, currentDatetime);
        allocations.addAll(maturedAllocations);

        // finalAt으로부터 아직 1년이 지나지 않은 납입 원금을 분류한다.
        List<LeftAmountDTO> immatureLeftAmounts =
                leftAmounts.stream()
                        .filter(
                                leftAmount ->
                                        leftAmount
                                                .getFinalAt()
                                                .plusYears(1)
                                                .isAfter(currentDatetime))
                        .toList();

        // 경과 원금에서 실제 배분된 합계를 빼서 조기인출이 필요한 금액을 구한다.
        BigDecimal maturedAllocatedAmount =
                maturedAllocations.stream()
                        .map(WithdrawalAllocationDTO::getAllocatedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        remainingRequest = remainingRequest.subtract(maturedAllocatedAmount);

        if (remainingRequest.compareTo(BigDecimal.ZERO) > 0) {
            if (!requestDTO.isEarlyWithdrawalAgreed()) {
                throw new EarlyWithdrawalConsentRequiredException("미경과 원금을 인출하려면 조기인출 동의가 필요합니다.");
            }
            List<WithdrawalAllocationDTO> immatureAllocations =
                    allocateImmaturePrincipalFifo(
                            remainingRequest, immatureLeftAmounts, currentDatetime);

            BigDecimal immatureAllocatedAmount =
                    immatureAllocations.stream()
                            .map(WithdrawalAllocationDTO::getAllocatedAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (immatureAllocatedAmount.compareTo(remainingRequest) < 0) {
                throw new WithdrawalNotAllowedException("인출 가능한 원금이 부족합니다.");
            }
            allocations.addAll(immatureAllocations);
            int changedBenefit = accountMapper.updateBenefitToImpossible(accountId);
            String benefitChangeReason = "조기인출로 인한 세제혜택 취소";
            if (changedBenefit == 1) {
                AccountBenefitLogDTO benefitLog =
                        AccountBenefitLogDTO.builder()
                                .accountId(accountId)
                                .prevStatus(account.getBenefit())
                                .newStatus(BenefitType.IMPOSSIBLE)
                                .changedAt(currentDatetime)
                                .reason(benefitChangeReason)
                                .build();

                int insertedLog = accountBenefitLogMapper.insertLog(benefitLog);

                if (insertedLog != 1) {
                    throw new AccountException("ACCOUNT_BENEFIT_LOG 저장에 실패했습니다.");
                }
            }
        }
        // 인출 저장
        WithdrawalDTO withdrawal =
                WithdrawalDTO.builder()
                        .accountId(accountId)
                        .requestedAmount(requestedAmount)
                        .processedAt(currentDatetime)
                        .destinationAccountNo(destinationGeneralAccount.getAccountNo())
                        .destinationGeneralAccountId(
                                destinationGeneralAccount.getGeneralAccountId())
                        .status(WithdrawalStatus.REQUESTED)
                        .build();
        int insertedRows = withdrawalMapper.insertWithdrawal(withdrawal);
        if (insertedRows != 1) {
            throw new WithdrawalProcessingException("WITHDRAWAL 저장에 실패했습니다.");
        }
        // 배분내역 저장
        for (WithdrawalAllocationDTO allocation : allocations) {
            allocation.setWithdrawalId(withdrawal.getWithdrawalId());
            int insertedAllocationRows = withdrawalMapper.insertWithdrawalAllocation(allocation);
            if (insertedAllocationRows != 1) {
                throw new WithdrawalProcessingException("WITHDRAWAL_ALLOCATION 저장에 실패했습니다.");
            }
            // 원금 차감
            if (allocation.getLeftAmountId() != null) {
                int deductedLeftAmountRows =
                        withdrawalMapper.deductLeftAmount(
                                allocation.getLeftAmountId(), allocation.getAllocatedAmount());
                if (deductedLeftAmountRows != 1) {
                    throw new WithdrawalProcessingException("LEFT_AMOUNT 차감에 실패했습니다.");
                }
            }
        }
        // RIA계좌의 총 잔액 차감
        int deductedAccountRows = withdrawalMapper.deductAccountAmount(accountId, requestedAmount);
        if (deductedAccountRows != 1) {
            throw new WithdrawalProcessingException("ACCOUNT 총 잔액을 차감하지 못했습니다.");
        }

        // 인출 상태 변경
        int updatedStatusRows =
                withdrawalMapper.updateWithdrawalStatus(
                        withdrawal.getWithdrawalId(), WithdrawalStatus.COMPLETED);
        if (updatedStatusRows != 1) {
            throw new WithdrawalProcessingException("인출 상태 변경에 실패했습니다.");
        }

        return WithdrawalResultDTO.builder()
                .withdrawalId(withdrawal.getWithdrawalId())
                .allocations(allocations)
                .build();
    }

    @Transactional
    public WithdrawalResultDTO withdrawForClosure(WithdrawalRequestDTO requestDTO) {

        return processWithdrawal(requestDTO, Status.CLOSURE_REQUESTED);
    }

    private List<WithdrawalAllocationDTO> allocateMaturedPrincipalFifo(
            BigDecimal amountToAllocate,
            List<LeftAmountDTO> maturedLeftAmounts,
            LocalDateTime currentDatetime) {
        List<WithdrawalAllocationDTO> allocations = new ArrayList<>();
        BigDecimal remainingAmount = amountToAllocate;

        for (LeftAmountDTO leftAmount : maturedLeftAmounts) {
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal allocatedAmount = remainingAmount.min(leftAmount.getCurAmount());

            allocations.add(
                    WithdrawalAllocationDTO.builder()
                            .leftAmountId(leftAmount.getLeftAmountId())
                            .allocatedAmount(allocatedAmount)
                            .withdrawalAt(currentDatetime)
                            .type(WithdrawalType.MATURED_PRINCIPAL_INCLUDED)
                            .build());

            remainingAmount = remainingAmount.subtract(allocatedAmount);
        }

        return allocations;
    }

    private List<WithdrawalAllocationDTO> allocateImmaturePrincipalFifo(
            BigDecimal amountToAllocate,
            List<LeftAmountDTO> immatureLeftAmounts,
            LocalDateTime currentDatetime) {
        List<WithdrawalAllocationDTO> allocations = new ArrayList<>();
        BigDecimal remainingAmount = amountToAllocate;

        for (LeftAmountDTO leftAmount : immatureLeftAmounts) {
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal allocatedAmount = remainingAmount.min(leftAmount.getCurAmount());

            allocations.add(
                    WithdrawalAllocationDTO.builder()
                            .leftAmountId(leftAmount.getLeftAmountId())
                            .allocatedAmount(allocatedAmount)
                            .withdrawalAt(currentDatetime)
                            .type(WithdrawalType.IMMATURE_PRINCIPAL_INCLUDED)
                            .build());

            remainingAmount = remainingAmount.subtract(allocatedAmount);
        }

        return allocations;
    }

    public boolean hasImmaturePrincipal(Long accountId) {
        return getImmaturePrincipalAmount(accountId).compareTo(BigDecimal.ZERO) > 0;
    }

    public BigDecimal getImmaturePrincipalAmount(Long accountId) {
        List<LeftAmountDTO> leftAmounts =
                withdrawalMapper.selectAvailableLeftAmountsByAccountId(accountId);

        LocalDateTime currentDatetime = businessClockService.now();

        return leftAmounts.stream()
                .filter(leftAmount -> leftAmount.getFinalAt().plusYears(1).isAfter(currentDatetime))
                .map(LeftAmountDTO::getCurAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getImmatureAllocatedAmount(Long withdrawalId) {
        return withdrawalMapper.selectImmatureAllocatedAmountByWithdrawalId(withdrawalId);
    }
}
