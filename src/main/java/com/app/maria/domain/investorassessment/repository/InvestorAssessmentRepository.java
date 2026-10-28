package com.app.maria.domain.investorassessment.repository;

import com.app.maria.domain.investorassessment.entity.InvestorAssessmentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorAssessmentRepository
        extends JpaRepository<InvestorAssessmentEntity, Long> {

    /** 가장 최근에 저장한 진단. 업무시계를 옮길 수 있어서 진단 시각이 아니라 저장 순서(id)로 고른다. */
    Optional<InvestorAssessmentEntity> findFirstByCustomerIdOrderByInvestorAssessmentIdDesc(
            Long customerId);
}
