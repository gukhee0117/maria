package com.app.maria.domain.account.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.CustomerConsentType;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MydataConsentServiceImpl implements MydataConsentService {
    private final AccountMapper accountMapper;
    private final BusinessClockService businessClockService;

    @Override
    public boolean isAgreed(Long customerId) {
        return accountMapper
                .selectLatestConsent(customerId, CustomerConsentType.MYDATA_PROVISION)
                .orElse(false);
    }

    @Override
    public void requireAgreed(Long customerId) {
        if (!isAgreed(customerId)) {
            throw new AppException(ErrorType.ACCOUNT_MYDATA_CONSENT_REQUIRED, customerId);
        }
    }

    @Override
    @Transactional
    public void record(Long customerId, boolean agreed) {
        if (accountMapper
                .selectLatestConsent(customerId, CustomerConsentType.MYDATA_PROVISION)
                .filter(v -> v == agreed)
                .isPresent()) {
            return;
        }
        accountMapper.insertConsent(
                customerId,
                CustomerConsentType.MYDATA_PROVISION,
                agreed,
                businessClockService.now());
    }
}
