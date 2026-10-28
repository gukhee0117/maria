package com.app.maria.domain.investorassessment.repository;

import com.app.maria.domain.investorassessment.entity.InvestorAssessmentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestorAssessmentRepository
        extends JpaRepository<InvestorAssessmentEntity, Long> {

    /** 가장 최근 진단. 같은 시각이면 나중에 저장된 행을 고른다. */
    Optional<InvestorAssessmentEntity>
            findFirstByCustomerIdOrderByAssessedAtDescInvestorAssessmentIdDesc(Long customerId);
}
