package com.app.maria.domain.account.service;

import com.app.maria.domain.account.dto.AccountBenefitLogDTO;
import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.dto.AccountStatusLogDTO;
import com.app.maria.domain.account.dto.response.AccountLogResponseDTO;
import com.app.maria.domain.account.mapper.AccountBenefitLogMapper;
import com.app.maria.domain.account.mapper.AccountStatusLogMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.account.type.Status;
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
public class AccountLogServiceImpl implements AccountLogService {

    private final AccountStatusLogMapper accountStatusLogMapper;
    private final AccountBenefitLogMapper accountBenefitLogMapper;
    private final String PREFIX = "LIMIT_CHANGE|";
    private final BusinessClockService businessClockService;

    @Override
    public void recordStatusChange(
            AccountDTO account, Status previousStatus, LocalDateTime changedAt, String reason) {
        AccountStatusLogDTO log =
                AccountStatusLogDTO.builder()
                        .accountId(account.getAccountId())
                        .prevStatus(previousStatus)
                        .newStatus(account.getStatus())
                        .changedAt(changedAt)
                        .reason(reason)
                        .build();
        if (accountStatusLogMapper.insertLog(log) != 1) {
            throw new AppException(
                    ErrorType.ACCOUNT_STATUS_LOG_SAVE_FAILED, account.getAccountId());
        }
    }

    @Override
    public List<AccountLogResponseDTO> getStatusLogs(Long accountId) {
        return accountStatusLogMapper.selectByAccountId(accountId).stream()
                .map(AccountLogResponseDTO::new)
                .toList();
    }

    @Override
    public String createLimitChangeReason(BigDecimal before, BigDecimal after) {
        return PREFIX
                + "from="
                + before.stripTrailingZeros().toPlainString()
                + "|to="
                + after.stripTrailingZeros().toPlainString();
    }

    @Override
    public void recordBenefitChange(
            AccountDTO account,
            BenefitType previousStatus,
            LocalDateTime changedAt,
            String reason) {
        AccountBenefitLogDTO log =
                AccountBenefitLogDTO.builder()
                        .accountId(account.getAccountId())
                        .prevStatus(previousStatus)
                        .newStatus(account.getBenefit())
                        .changedAt(changedAt)
                        .reason(reason)
                        .build();
        if (accountBenefitLogMapper.insertLog(log) != 1) {
            throw new AppException(
                    ErrorType.ACCOUNT_BENEFIT_LOG_SAVE_FAILED, account.getAccountId());
        }
    }

    @Override
    public List<AccountBenefitLogDTO> getBenefitLogs(Long accountId) {
        return accountBenefitLogMapper.selectByAccountId(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public int getTodayProcessedAccountCount(Status newStatus) {
        LocalDateTime start = businessClockService.now().toLocalDate().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return accountStatusLogMapper.countByNewStatusBetween(newStatus, start, end);
    }
}
