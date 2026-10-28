package com.app.maria.domain.investorassessment.entity;

import com.app.maria.domain.customer.entity.InvestorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 투자성향 진단 이력. 재진단하면 행이 추가되고, 현재 성향은 customer.investor_type에 반영된다. */
@Entity
@Table(name = "investor_assessment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestorAssessmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "investor_assessment_id")
    private Long investorAssessmentId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "investor_type", nullable = false, length = 20)
    private InvestorType investorType;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "answers_json", nullable = false)
    private String answersJson;

    @Column(name = "assessed_at", nullable = false)
    private LocalDateTime assessedAt;

    public static InvestorAssessmentEntity of(
            Long customerId,
            int totalScore,
            InvestorType investorType,
            String answersJson,
            LocalDateTime assessedAt) {
        if (investorType == InvestorType.UNASSIGNED) {
            throw new IllegalArgumentException("미진단은 진단 결과로 저장할 수 없습니다.");
        }
        InvestorAssessmentEntity assessment = new InvestorAssessmentEntity();
        assessment.customerId = customerId;
        assessment.totalScore = totalScore;
        assessment.investorType = investorType;
        assessment.answersJson = answersJson;
        assessment.assessedAt = assessedAt;
        return assessment;
    }
}
