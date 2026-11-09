package com.app.maria.domain.account.service;

/** 마이데이터 제공 동의. 동의 이력(customer_consent)의 가장 최근 값이 현재 동의 여부다. */
public interface MydataConsentService {
    boolean isAgreed(Long customerId);

    /** 동의하지 않았으면 마이데이터를 조회할 수 없다. */
    void requireAgreed(Long customerId);

    /** 현재 값과 같으면 이력을 늘리지 않는다. */
    void record(Long customerId, boolean agreed);
}
