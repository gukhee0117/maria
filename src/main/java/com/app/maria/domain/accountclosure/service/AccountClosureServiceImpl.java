package com.app.maria.domain.accountclosure.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.exception.AccountNotFoundException;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.service.AccountLogService;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.accountclosure.dto.AccountClosureDTO;
import com.app.maria.domain.accountclosure.dto.request.AccountClosureApplyRequestDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureDetailResponseDTO;
import com.app.maria.domain.accountclosure.dto.response.AccountClosureResponseDTO;
import com.app.maria.domain.accountclosure.exception.AccountClosureNotAllowedException;
import com.app.maria.domain.accountclosure.exception.AccountClosureNotFoundException;
import com.app.maria.domain.accountclosure.exception.AccountClosureProcessingException;
import com.app.maria.domain.accountclosure.exception.AccountClosureStateConflictException;
import com.app.maria.domain.accountclosure.mapper.AccountClosureMapper;
import com.app.maria.domain.accountclosure.type.AccountClosureAuditLogReasonCode;
import com.app.maria.domain.accountclosure.type.AccountClosureStatus;
import com.app.maria.domain.withdrawal.dto.WithdrawalResultDTO;
import com.app.maria.domain.withdrawal.dto.request.WithdrawalRequestDTO;
import com.app.maria.domain.withdrawal.exception.EarlyWithdrawalConsentRequiredException;
import com.app.maria.domain.withdrawal.service.WithdrawalService;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.client.generalaccount.GeneralAccountClient;
import com.app.maria.global.client.generalaccount.dto.request.GeneralAccountRequestDTO;
import com.app.maria.global.client.generalaccount.dto.response.GeneralAccountResponseDTO;
import com.app.maria.global.client.generalaccount.type.GeneralAccountStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class AccountClosureServiceImpl implements AccountClosureService {
    private final AccountMapper accountMapper;
    private final AccountLogService accountLogService;
    private final AccountClosureMapper accountClosureMapper;
    private final BusinessClockService businessClockService;
    private final GeneralAccountClient generalAccountClient;
    private final WithdrawalService withdrawalService;
    private final AuditLogService auditLogService;
    private final AuditActorProvider auditActorProvider;

    @Override
    public Long applyClosure(Long customerId, AccountClosureApplyRequestDTO requestDTO) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(() -> new AccountNotFoundException("해지할 계좌가 존재하지 않습니다."));
        if (account.getStatus() != Status.OPENED) {
            throw new AccountClosureNotAllowedException("개설 완료된 계좌만 해지를 할 수 있습니다.");
        }
        String ciHash =
                accountMapper
                        .selectCiHashByCustomerId(account.getCustomerId())
                        .orElseThrow(() -> new AccountNotFoundException("존재하지않는 고객입니다."));

        GeneralAccountRequestDTO generalAccountRequest =
                GeneralAccountRequestDTO.builder()
                        .ciHash(ciHash)
                        .generalAccountId(requestDTO.getDestinationGeneralAccountId())
                        .build();
        GeneralAccountResponseDTO response =
                generalAccountClient.verifyGeneralAccount(generalAccountRequest);

        if (response.getStatus() != GeneralAccountStatus.ACTIVE) {
            throw new AccountClosureNotAllowedException("활성 상태의 일반계좌만 해지 정산 계좌로 선택할 수 있습니다.");
        }
        if (!requestDTO.isEarlyWithdrawalAgreed()
                && withdrawalService.hasImmaturePrincipal(account.getAccountId())) {
            throw new EarlyWithdrawalConsentRequiredException(
                    "1년 미경과 원금이 있어 계좌 해지를 위해 조기인출 동의가 필요합니다.");
        }
        int updatedAccountRows = accountMapper.requestClosure(account.getAccountId());
        if (updatedAccountRows != 1) {
            throw new AccountClosureStateConflictException("계좌 상태가 변경되어 해지를 신청할 수 없습니다.");
        }
        AccountClosureDTO closure =
                AccountClosureDTO.builder()
                        .accountId(account.getAccountId())
                        .destinationGeneralAccountId(response.getGeneralAccountId())
                        .earlyWithdrawalAgreed(requestDTO.isEarlyWithdrawalAgreed())
                        .status(AccountClosureStatus.REQUESTED)
                        .requestedAt(businessClockService.now())
                        .build();
        int insertedClosureRows = accountClosureMapper.insertClosureRequest(closure);
        if (insertedClosureRows != 1) {
            throw new AccountClosureProcessingException("계좌 해지 신청 저장에 실패했습니다.");
        }
        recordStatusChange(
                account.getAccountId(),
                Status.OPENED,
                Status.CLOSURE_REQUESTED,
                closure.getRequestedAt(),
                "계좌 해지 신청");
        logAccountStatusChange(
                auditActorProvider.getCurrentAdminId(),
                account.getAccountId(),
                Status.OPENED,
                Status.CLOSURE_REQUESTED,
                AccountClosureAuditLogReasonCode.ACCOUNT_CLOSURE_REQUESTED);
        return closure.getClosureRequestId();
    }

    @Override
    public void rejectClosure(Long adminId, Long closureRequestId, String reason) {
        // 신청 건 잠금 조회
        AccountClosureDTO closure =
                accountClosureMapper
                        .selectByIdForUpdate(closureRequestId)
                        .orElseThrow(
                                () -> new AccountClosureNotFoundException("계좌 해지 신청을 찾을 수 없습니다."));
        // REQUESTED상태 검증
        if (closure.getStatus() != AccountClosureStatus.REQUESTED) {
            throw new AccountClosureNotAllowedException("이미 처리된 계좌 해지 신청입니다.");
        }
        closure.setProcessedAt(businessClockService.now());
        closure.setProcessedBy(adminId);
        closure.setRejectionReason(reason);

        int rejectedClosureRows = accountClosureMapper.rejectClosureRequest(closure);
        if (rejectedClosureRows != 1) {
            throw new AccountClosureProcessingException("계좌 해지 신청 반려 처리에 실패했습니다.");
        }
        int reopenedRows = accountMapper.reopenAfterClosureRejection(closure.getAccountId());
        if (reopenedRows != 1) {
            throw new AccountClosureProcessingException("해지 반려 후 계좌 상태 복구에 실패했습니다.");
        }
        recordStatusChange(
                closure.getAccountId(),
                Status.CLOSURE_REQUESTED,
                Status.OPENED,
                closure.getProcessedAt(),
                "계좌 해지 신청 반려: " + reason);
        logAccountStatusChange(
                adminId,
                closure.getAccountId(),
                Status.CLOSURE_REQUESTED,
                Status.OPENED,
                AccountClosureAuditLogReasonCode.ACCOUNT_CLOSURE_REJECTED);
    }

    @Override
    public void approveClosure(Long adminId, Long closureRequestId) {
        AccountClosureDTO closure =
                accountClosureMapper
                        .selectByIdForUpdate(closureRequestId)
                        .orElseThrow(
                                () -> new AccountClosureNotFoundException("계좌 해지 신청을 찾을 수 없습니다."));
        if (closure.getStatus() != AccountClosureStatus.REQUESTED) {
            throw new AccountClosureNotAllowedException("이미 처리된 계좌 해지 신청입니다.");
        }
        AccountDTO account =
                accountMapper
                        .selectByAccountIdForUpdate(closure.getAccountId())
                        .orElseThrow(() -> new AccountNotFoundException("해지할 계좌를 찾을 수 없습니다."));
        if (account.getStatus() != Status.CLOSURE_REQUESTED) {
            throw new AccountClosureNotAllowedException("해지 신청 상태의 계좌만 승인할 수 있습니다.");
        }
        Long withdrawalId = null;
        if (account.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            WithdrawalRequestDTO forcedWithdrawalRequest =
                    WithdrawalRequestDTO.builder()
                            .accountId(account.getAccountId())
                            .requestedAmount(account.getAmount())
                            .earlyWithdrawalAgreed(closure.isEarlyWithdrawalAgreed())
                            .destinationGeneralAccountId(closure.getDestinationGeneralAccountId())
                            .build();

            WithdrawalResultDTO forcedWithdrawalResult =
                    withdrawalService.withdrawForClosure(forcedWithdrawalRequest);

            withdrawalId = forcedWithdrawalResult.getWithdrawalId();
            if (withdrawalId == null) {
                throw new AccountClosureProcessingException("강제 인출 식별자를 확인할 수 없습니다.");
            }
        }
        AccountDTO accountBeforeClosure =
                accountMapper
                        .selectByAccountIdForUpdate(account.getAccountId())
                        .orElseThrow(() -> new AccountNotFoundException("해지 처리할 계좌를 찾을 수 없습니다."));
        if (accountBeforeClosure.getStatus() != Status.CLOSURE_REQUESTED) {
            throw new AccountClosureNotAllowedException("해지 신청 상태가 변경되어 계좌를 해지할 수 없습니다.");
        }
        if (accountBeforeClosure.getAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new AccountClosureProcessingException("강제인출 후에도 계좌 잔액이 남아 있어 해지할 수 없습니다.");
        }

        int closedAccountRows = accountMapper.completeClosure(account.getAccountId());
        if (closedAccountRows != 1) {
            throw new AccountClosureProcessingException("계좌 상태가 변경되어 해지 처리에 실패했습니다.");
        }
        closure.setProcessedAt(businessClockService.now());
        closure.setProcessedBy(adminId);
        closure.setWithdrawalId(withdrawalId);

        int completedClosureRows = accountClosureMapper.completeClosureRequest(closure);
        if (completedClosureRows != 1) {
            throw new AccountClosureProcessingException("계좌 해지 신청 완료 처리에 실패했습니다.");
        }
        recordStatusChange(
                closure.getAccountId(),
                Status.CLOSURE_REQUESTED,
                Status.CLOSED,
                closure.getProcessedAt(),
                "계좌 해지 완료");
        logAccountStatusChange(
                adminId,
                closure.getAccountId(),
                Status.CLOSURE_REQUESTED,
                Status.CLOSED,
                AccountClosureAuditLogReasonCode.ACCOUNT_CLOSURE_APPROVED);
    }

    @Override
    public List<AccountClosureResponseDTO> getClosures(AccountClosureStatus status) {
        List<AccountClosureDTO> closures = accountClosureMapper.selectByStatus(status);

        return closures.stream().map(AccountClosureResponseDTO::from).toList();
    }

    @Override
    public AccountClosureDetailResponseDTO getClosure(Long closureRequestId) {
        AccountClosureDTO closure =
                accountClosureMapper
                        .selectById(closureRequestId)
                        .orElseThrow(
                                () -> new AccountClosureNotFoundException("계좌 해지 신청을 찾을 수 없습니다."));
        BigDecimal immaturePrincipalAmount =
                switch (closure.getStatus()) {
                    case REQUESTED ->
                            withdrawalService.getImmaturePrincipalAmount(closure.getAccountId());
                    case COMPLETED ->
                            closure.getWithdrawalId() == null
                                    ? BigDecimal.ZERO
                                    : withdrawalService.getImmatureAllocatedAmount(
                                            closure.getWithdrawalId());
                    case REJECTED -> BigDecimal.ZERO;
                };

        return AccountClosureDetailResponseDTO.from(closure, immaturePrincipalAmount);
    }

    private void logAccountStatusChange(
            Long adminId,
            Long accountId,
            Status beforeStatus,
            Status afterStatus,
            AccountClosureAuditLogReasonCode reasonCode) {
        auditLogService.log(
                AuditLogDTO.builder()
                        .adminId(adminId)
                        .targetTable("ACCOUNT")
                        .targetPk(String.valueOf(accountId))
                        .beforeValue(beforeStatus.name())
                        .afterValue(afterStatus.name())
                        .reasonCode(reasonCode.name())
                        .build());
    }

    private void recordStatusChange(
            Long accountId,
            Status previousStatus,
            Status newStatus,
            LocalDateTime changedAt,
            String reason) {
        AccountDTO changedAccount =
                AccountDTO.builder().accountId(accountId).status(newStatus).build();
        accountLogService.recordStatusChange(changedAccount, previousStatus, changedAt, reason);
    }
}
