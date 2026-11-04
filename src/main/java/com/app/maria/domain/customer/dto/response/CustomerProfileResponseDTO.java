package com.app.maria.domain.customer.dto.response;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.entity.InvestorAssessmentEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 정보 화면이 한 번에 쓰는 값. 연락처는 원문이고 마스킹은 화면이 한다. 닉네임은 조회만 하며 없으면 null이다. 진단 기록이 없는 고객(시드 고객)은 진단
 * 시각·점수·이력이 비어 있다. account는 RIA 계좌가 없으면 null이고, 한도·보유금액·세제혜택은 이 응답에 넣지 않는다.
 */
public record CustomerProfileResponseDTO(
        String name,
        LocalDate birthDate,
        String phone,
        String email,
        String nickname,
        InvestorType investorType,
        boolean assessed,
        LocalDateTime assessedAt,
        LocalDateTime expiresAt,
        boolean expired,
        Integer totalScore,
        boolean investorTypePublic,
        List<AssessmentHistoryItem> assessmentHistory,
        AccountSummary account) {

    public record AssessmentHistoryItem(
            LocalDateTime assessedAt, int totalScore, InvestorType investorType) {}

    /** accountNo·openedAt은 개설 전에는 null. status는 DB 값 그대로(해지 CLOSED도 준다). */
    public record AccountSummary(
            String accountNo, Status status, LocalDateTime openedAt, LocalDateTime appliedAt) {
        static AccountSummary of(AccountDTO account) {
            return new AccountSummary(
                    account.getAccountNo(),
                    account.getStatus(),
                    account.getOpenedAt(),
                    account.getCreatedAt());
        }
    }

    public static CustomerProfileResponseDTO of(
            CustomerEntity customer,
            InvestorAssessmentStatusResponseDTO status,
            List<InvestorAssessmentEntity> recentAssessments,
            AccountDTO account) {
        Integer totalScore =
                recentAssessments.isEmpty() ? null : recentAssessments.get(0).getTotalScore();
        return new CustomerProfileResponseDTO(
                customer.getName(),
                customer.getBirthDate(),
                customer.getPhone(),
                customer.getEmail(),
                customer.getNickname(),
                status.getInvestorType(),
                status.isAssessed(),
                status.getAssessedAt(),
                status.getExpiresAt(),
                status.isExpired(),
                totalScore,
                customer.isInvestorTypePublic(),
                recentAssessments.stream()
                        .map(
                                a ->
                                        new AssessmentHistoryItem(
                                                a.getAssessedAt(),
                                                a.getTotalScore(),
                                                a.getInvestorType()))
                        .toList(),
                account == null ? null : AccountSummary.of(account));
    }
}
