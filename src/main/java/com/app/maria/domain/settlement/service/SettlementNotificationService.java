package com.app.maria.domain.settlement.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.settlement.notification.SettlementSseService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class SettlementNotificationService {

    private final AccountMapper accountMapper;
    private final SettlementSseService settlementSseService;

    public SseEmitter subscribeCustomer(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
        return settlementSseService.subscribeAccount(account.getAccountId());
    }
}
