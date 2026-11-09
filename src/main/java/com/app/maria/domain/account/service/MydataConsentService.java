package com.app.maria.domain.account.service;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 마이데이터 제공 동의. 동의 이력(customer_consent)의 가장 최근 값이 현재 동의 여부다. */
@Service
@RequiredArgsConstructor
public class MydataConsentService {
    private final AccountMapper accountMapper;
    private final BusinessClockService businessClockService;

    public boolean isAgreed(Long customerId) {
        return accountMapper.selectLatestMydataConsent(customerId).orElse(false);
    }

    /** 동의하지 않았으면 마이데이터를 조회할 수 없다. */
    public void requireAgreed(Long customerId) {
        if (!isAgreed(customerId)) {
            throw new AppException(ErrorType.ACCOUNT_MYDATA_CONSENT_REQUIRED, customerId);
        }
    }

    /** 현재 값과 같으면 이력을 늘리지 않는다. */
    @Transactional
    public void record(Long customerId, boolean agreed) {
        if (accountMapper
                .selectLatestMydataConsent(customerId)
                .filter(v -> v == agreed)
                .isPresent()) {
            return;
        }
        accountMapper.insertMydataConsent(customerId, agreed, businessClockService.now());
    }
}
