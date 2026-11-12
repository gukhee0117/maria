package com.app.maria.domain.stockpackage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.domain.stockpackage.dto.response.StockTendencyResponseDTO;
import com.app.maria.domain.stockpackage.type.StockPackageSentenceTest;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockTendencyServiceImplTest {
    private static final Long CUSTOMER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Mock private InvestorAssessmentService assessmentService;
    @Mock private BusinessClockService clock;
    @InjectMocks private StockTendencyServiceImpl service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(NOW);
        // 위험중립형(4등급까지)
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.NEUTRAL,
                                NOW.minusMonths(1),
                                NOW.plusMonths(23),
                                false));
    }

    @Test
    @DisplayName("설문 성향과 허용 위험등급을 돌려준다")
    void survey() {
        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        assertThat(r.getSurvey().isAvailable()).isTrue();
        assertThat(r.getSurvey().getLabel()).isEqualTo("위험중립형");
        assertThat(r.getSurvey().getAllowedMinGrade()).isEqualTo(4);
        assertThat(allText(r)).contains("위험중립형").contains("4등급");
    }

    @Test
    @DisplayName("진단이 없거나 만료되면 설문 성향은 확인할 수 없다")
    void surveyUnavailable() {
        when(assessmentService.getMine(CUSTOMER_ID))
                .thenReturn(InvestorAssessmentStatusResponseDTO.unassigned());

        StockTendencyResponseDTO r = service.getTendency(CUSTOMER_ID);

        assertThat(r.getSurvey().isAvailable()).isFalse();
        assertThat(r.getSurvey().getAllowedMinGrade()).isNull();
        assertThat(allText(r)).contains("설문 투자성향은 확인할 수 없어요.");
    }

    @Test
    @DisplayName("설명에는 권유·평가 표현이 없다")
    void explanationHasNoAdvice() {
        String all = allText(service.getTendency(CUSTOMER_ID));

        for (String banned : StockPackageSentenceTest.BANNED) {
            assertThat(all).as("금지 표현 '%s'", banned).doesNotContain(banned);
        }
    }

    private static String allText(StockTendencyResponseDTO r) {
        return r.getExplanation().stream()
                .map(b -> b.getDefinition() + " " + String.join(" ", b.getFacts()))
                .collect(Collectors.joining(" "));
    }
}
