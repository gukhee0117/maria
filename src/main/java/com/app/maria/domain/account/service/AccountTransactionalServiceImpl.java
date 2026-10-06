package com.app.maria.domain.account.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.request.AccountReapplyRequestDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.AuditLogReasonCode;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountTransactionalServiceImpl implements AccountTransactionalService {
    private static final int ACCOUNT_NO_RETRY_LIMIT = 5;
    // 계좌번호 = 회사코드(3자리, 고정) + 고유번호(7자리, 랜덤) = 총 10자리.
    // 실제 계좌번호가 앞자리를 발급기관 코드로 고정하는 관례를 반영 — 순수 랜덤 10자리는 비현실적.
    private static final String COMPANY_CODE = "731";
    private static final long SERIAL_MAX_EXCLUSIVE = 10_000_000L;
    private final AccountMapper accountMapper;
    private final AccountLogService accountLogService;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDTO updateLimit(
            Long adminId,
            Long customerId,
            BigDecimal expectedCurrentLimit,
            BigDecimal newLimit,
            LocalDateTime changedAt) {
        AccountDTO account = findByCustomer(customerId);
        if (account.getStatus() != Status.APPLIED && account.getStatus() != Status.OPENED) {
            throw new AppException(ErrorType.ACCOUNT_STATE_NOT_ALLOWED, account.getStatus());
        }
        if (account.getLimitAmount().compareTo(newLimit) == 0) {
            throw new AppException(ErrorType.ACCOUNT_LIMIT_UNCHANGED, newLimit);
        }
        if (expectedCurrentLimit == null
                || account.getLimitAmount().compareTo(expectedCurrentLimit) != 0) {
            throw new AppException(
                    ErrorType.ACCOUNT_CONCURRENT_MODIFICATION, account.getAccountId());
        }
        BigDecimal ownUsedAndReservedAmount =
                accountMapper.selectOwnUsedAndReservedAmount(account.getAccountId());
        if (newLimit.compareTo(ownUsedAndReservedAmount) < 0) {
            throw new AppException(ErrorType.ACCOUNT_LIMIT_BELOW_USED_AMOUNT, newLimit);
        }
        if (accountMapper.updateLimit(
                        account.getAccountId(), account.getStatus(), expectedCurrentLimit, newLimit)
                != 1) {
            throw new AppException(
                    ErrorType.ACCOUNT_CONCURRENT_MODIFICATION, account.getAccountId());
        }
        AccountDTO updatedAccount = find(account.getAccountId());
        logAudit(
                adminId,
                updatedAccount.getAccountId(),
                "limitAmount=" + account.getLimitAmount(),
                "limitAmount=" + updatedAccount.getLimitAmount(),
                AuditLogReasonCode.ACCOUNT_CHANGE_LIMIT_AMOUNT);
        String logMessage =
                accountLogService.createLimitChangeReason(
                        account.getLimitAmount(), updatedAccount.getLimitAmount());
        accountLogService.recordStatusChange(
                updatedAccount, account.getStatus(), changedAt, logMessage);
        return updatedAccount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDTO apply(
            Long adminId, AccountDTO account, LocalDateTime appliedAt, boolean autoApprove) {
        if (accountMapper.existsByCustomerId(account.getCustomerId())) {
            throw new AppException(ErrorType.ACCOUNT_ALREADY_EXISTS, account.getCustomerId());
        }
        account.setCreatedAt(appliedAt);
        try {
            if (accountMapper.insertApplication(account) != 1) {
                throw new AppException(
                        ErrorType.ACCOUNT_APPLICATION_SAVE_FAILED, account.getCustomerId());
            }
        } catch (DuplicateKeyException e) {
            throw new AppException(ErrorType.ACCOUNT_ALREADY_EXISTS, account.getCustomerId());
        }
        AccountDTO appliedAccount = findByCustomer(account.getCustomerId());
        accountLogService.recordStatusChange(appliedAccount, null, appliedAt, "최초 개설 신청");
        logAudit(
                adminId,
                appliedAccount.getAccountId(),
                null,
                appliedAccount.getStatus().name(),
                AuditLogReasonCode.ACCOUNT_APPLY);
        if (!autoApprove) {
            return appliedAccount;
        }
        open(appliedAccount, appliedAt);
        AccountDTO openedAccount = findByCustomer(account.getCustomerId());
        assertStatus(openedAccount, Status.OPENED);
        accountLogService.recordStatusChange(openedAccount, Status.APPLIED, appliedAt, "자동 판정 승인");
        assertBenefit(openedAccount, BenefitType.POSSIBLE);
        accountLogService.recordBenefitChange(openedAccount, null, appliedAt, "계좌 개설에 따른 세제혜택 가능");
        logAudit(
                adminId,
                openedAccount.getAccountId(),
                Status.APPLIED.name(),
                openedAccount.getStatus().name(),
                AuditLogReasonCode.ACCOUNT_OPENED);
        return openedAccount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDTO approve(
            Long adminId, Long accountId, BigDecimal expectedLimit, LocalDateTime openedAt) {
        AccountDTO account = find(accountId);
        account.setLimitAmount(expectedLimit);
        open(account, openedAt);
        AccountDTO openedAccount = find(accountId);
        assertStatus(openedAccount, Status.OPENED);
        accountLogService.recordStatusChange(
                openedAccount, account.getStatus(), openedAt, "사용자 계좌 개설");
        assertBenefit(openedAccount, BenefitType.POSSIBLE);
        accountLogService.recordBenefitChange(openedAccount, null, openedAt, "계좌 개설에 따른 세제혜택 가능");
        logAudit(
                adminId,
                openedAccount.getAccountId(),
                account.getStatus().name(),
                openedAccount.getStatus().name(),
                AuditLogReasonCode.ACCOUNT_OPENED);
        return openedAccount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDTO reject(Long adminId, Long accountId, String reason, LocalDateTime changedAt) {
        AccountDTO account = find(accountId);
        if (accountMapper.reject(account) != 1) {
            throw new AppException(ErrorType.ACCOUNT_CONCURRENT_MODIFICATION, accountId);
        }
        AccountDTO rejectedAccount = find(accountId);
        assertStatus(rejectedAccount, Status.REJECTED);
        accountLogService.recordStatusChange(
                rejectedAccount, account.getStatus(), changedAt, reason);
        logAudit(
                adminId,
                rejectedAccount.getAccountId(),
                account.getStatus().name(),
                rejectedAccount.getStatus().name(),
                AuditLogReasonCode.ACCOUNT_REJECTED);
        return rejectedAccount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDTO reapply(
            Long adminId,
            Long accountId,
            AccountReapplyRequestDTO request,
            LocalDateTime appliedAt) {
        AccountDTO account = find(accountId);
        AccountDTO newAccount = request.toAccountDTO();
        newAccount.setAccountId(accountId);
        if (newAccount.getLimitAmount() == null) {
            newAccount.setLimitAmount(account.getLimitAmount());
        }
        if (accountMapper.reapply(newAccount) != 1) {
            throw new AppException(ErrorType.ACCOUNT_CONCURRENT_MODIFICATION, accountId);
        }
        AccountDTO appliedAccount = find(accountId);
        assertStatus(appliedAccount, Status.APPLIED);
        accountLogService.recordStatusChange(
                appliedAccount, account.getStatus(), appliedAt, "사용자 계좌 개설 재신청");
        logAudit(
                adminId,
                appliedAccount.getAccountId(),
                account.getStatus().name(),
                appliedAccount.getStatus().name(),
                AuditLogReasonCode.ACCOUNT_REAPPLY);
        return appliedAccount;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountDTO override(
            Long adminId, Long accountId, String reason, LocalDateTime openedAt) {
        AccountDTO account = find(accountId);
        if (account.getStatus() != Status.REJECTED) {
            throw new AppException(ErrorType.ACCOUNT_STATE_NOT_ALLOWED, account.getStatus());
        }
        open(account, openedAt);
        AccountDTO openedAccount = find(accountId);
        assertStatus(openedAccount, Status.OPENED);
        accountLogService.recordStatusChange(openedAccount, Status.REJECTED, openedAt, reason);
        assertBenefit(openedAccount, BenefitType.POSSIBLE);
        accountLogService.recordBenefitChange(openedAccount, null, openedAt, "계좌 개설에 따른 세제혜택 가능");
        logAudit(
                adminId,
                openedAccount.getAccountId(),
                Status.REJECTED.name(),
                openedAccount.getStatus().name(),
                AuditLogReasonCode.ACCOUNT_OVERRIDE_OPENED);
        return openedAccount;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateAmount(AccountDTO provisionalAmountDelta) {
        find(provisionalAmountDelta.getAccountId());
        if (accountMapper.updateProvisionalAmount(provisionalAmountDelta) != 1) {
            throw new AppException(
                    ErrorType.ACCOUNT_AMOUNT_UPDATE_FAILED, provisionalAmountDelta.getAccountId());
        }
    }

    private void open(AccountDTO account, LocalDateTime openedAt) {
        boolean override = account.getStatus() == Status.REJECTED;
        account.setOpenedAt(openedAt);
        for (int i = 0; i < ACCOUNT_NO_RETRY_LIMIT; i++) {
            account.setAccountNo(
                    COMPANY_CODE
                            + String.format(
                                    "%07d",
                                    ThreadLocalRandom.current().nextLong(SERIAL_MAX_EXCLUSIVE)));
            try {
                if ((override
                                ? accountMapper.overrideToOpened(account)
                                : accountMapper.approve(account))
                        == 1) {
                    return;
                }
                throw new AppException(
                        ErrorType.ACCOUNT_CONCURRENT_MODIFICATION, account.getAccountId());
            } catch (DuplicateKeyException e) {
                if (i == ACCOUNT_NO_RETRY_LIMIT - 1) {
                    throw new AppException(
                            ErrorType.ACCOUNT_NUMBER_GENERATION_FAILED, account.getCustomerId());
                }
            }
        }
    }

    @Override
    @Transactional
    public boolean changeBenefit(
            Long accountId, BenefitType newStatus, String reason, LocalDateTime changedAt) {
        AccountDTO account = find(accountId);
        BenefitType previousStatus = account.getBenefit();

        if (previousStatus == newStatus || previousStatus == BenefitType.IMPOSSIBLE) {
            return false;
        }

        // 조회와 변경 사이에 다른 요청이 상태를 바꿨으면 0행이 되어 이력도 남기지 않는다.
        if (accountMapper.updateBenefit(accountId, newStatus, previousStatus) != 1) {
            return false;
        }

        account.setBenefit(newStatus);
        accountLogService.recordBenefitChange(account, previousStatus, changedAt, reason);
        return true;
    }

    private AccountDTO find(Long id) {
        return accountMapper
                .selectByAccountId(id)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, id));
    }

    private AccountDTO findByCustomer(Long id) {
        return accountMapper
                .selectByCustomerId(id)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, id));
    }

    private void logAudit(
            Long adminId,
            Long accountId,
            String beforeValue,
            String afterValue,
            AuditLogReasonCode reasonCode) {
        auditLogService.log(
                AuditLogDTO.builder()
                        .adminId(adminId)
                        .targetTable("ACCOUNT")
                        .targetPk(String.valueOf(accountId))
                        .beforeValue(beforeValue)
                        .afterValue(afterValue)
                        .reasonCode(reasonCode.name())
                        .build());
    }

    private void assertStatus(AccountDTO account, Status status) {
        if (account.getStatus() != status) {
            throw new AppException(ErrorType.ACCOUNT_STATE_UPDATE_FAILED, account.getAccountId());
        }
    }

    private void assertBenefit(AccountDTO account, BenefitType benefitType) {
        if (account.getBenefit() != benefitType) {
            throw new AppException(ErrorType.ACCOUNT_BENEFIT_UPDATE_FAILED, account.getAccountId());
        }
    }
}
