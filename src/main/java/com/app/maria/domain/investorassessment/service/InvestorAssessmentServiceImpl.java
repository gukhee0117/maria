package com.app.maria.domain.investorassessment.service;

import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentResultResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorQuestionResponseDTO;
import com.app.maria.domain.investorassessment.entity.InvestorAssessmentEntity;
import com.app.maria.domain.investorassessment.entity.choice.InvestorQuestion;
import com.app.maria.domain.investorassessment.repository.InvestorAssessmentRepository;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InvestorAssessmentServiceImpl implements InvestorAssessmentService {
    /** 진단은 이 기간 동안 유효하다. */
    private static final int VALID_MONTHS = 24;

    private final CustomerRepository customerRepository;
    private final InvestorAssessmentRepository assessmentRepository;
    private final BusinessClockService businessClockService;
    private final ObjectMapper objectMapper;

    @Override
    public List<InvestorQuestionResponseDTO> questions() {
        return Arrays.stream(InvestorQuestion.values())
                .map(
                        question ->
                                new InvestorQuestionResponseDTO(
                                        question.answerKey(),
                                        question.category(),
                                        question.text(),
                                        question.multiple(),
                                        question.choices().stream()
                                                .map(
                                                        choice ->
                                                                new InvestorQuestionResponseDTO
                                                                        .Choice(
                                                                        choice.name(),
                                                                        choice.label()))
                                                .toList()))
                .toList();
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public InvestorAssessmentResultResponseDTO submit(
            Long customerId, InvestorAssessmentSubmitRequestDTO request) {
        // 동시에 재진단이 들어와도 마지막 진단이 현재 성향이 되도록 고객 행을 잠근다.
        CustomerEntity customer =
                customerRepository
                        .findByIdForUpdate(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
        int totalScore = InvestorScoreCalculator.totalScore(request);
        InvestorType diagnosed = InvestorScoreCalculator.toType(totalScore);
        LocalDateTime assessedAt = businessClockService.now();

        assessmentRepository.save(
                InvestorAssessmentEntity.of(
                        customerId, totalScore, diagnosed, answersJson(request), assessedAt));
        customer.applyInvestorType(diagnosed);

        return new InvestorAssessmentResultResponseDTO(
                totalScore, diagnosed, assessedAt, assessedAt.plusMonths(VALID_MONTHS));
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public InvestorAssessmentStatusResponseDTO getMine(Long customerId) {
        CustomerEntity customer =
                customerRepository
                        .findById(customerId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.CUSTOMER_NOT_FOUND, customerId));
        if (customer.getInvestorType() == InvestorType.UNASSIGNED) {
            return InvestorAssessmentStatusResponseDTO.unassigned();
        }
        Optional<InvestorAssessmentEntity> latest =
                assessmentRepository.findFirstByCustomerIdOrderByInvestorAssessmentIdDesc(
                        customerId);
        if (latest.isEmpty()) {
            return InvestorAssessmentStatusResponseDTO.withoutRecord(customer.getInvestorType());
        }
        LocalDateTime assessedAt = latest.get().getAssessedAt();
        LocalDateTime expiresAt = assessedAt.plusMonths(VALID_MONTHS);
        boolean expired = !businessClockService.now().isBefore(expiresAt);
        return InvestorAssessmentStatusResponseDTO.of(
                customer.getInvestorType(), assessedAt, expiresAt, expired);
    }

    // 투자경험은 SortedSet(enum 순서)이라 같은 응답이면 같은 JSON이 된다.
    private String answersJson(InvestorAssessmentSubmitRequestDTO request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("설문 응답 JSON 변환 실패", e);
        }
    }
}
