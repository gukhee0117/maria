package com.app.maria.domain.customer.repository;

import com.app.maria.domain.customer.entity.InvestorType;

/** 커뮤니티 목록에 작성자를 보여주는 데 필요한 값만 담은 조회 결과(고객 전체를 읽지 않는다). */
public interface CustomerCommunityProfile {
    Long getCustomerId();

    String getNickname();

    InvestorType getInvestorType();

    boolean getInvestorTypePublic();
}
