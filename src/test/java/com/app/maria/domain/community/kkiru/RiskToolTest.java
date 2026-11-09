package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskToolTest {
    @org.junit.jupiter.api.BeforeEach
    void classificationCompatibility() {
        org.mockito.Mockito.lenient()
                .doCallRealMethod()
                .when(riskGradeService)
                .findClassificationByTicker(org.mockito.ArgumentMatchers.any());
    }

    @Mock private RiskGradeService riskGradeService;
    @InjectMocks private RiskTool tool;

    private static KkiruQuestion question(String text) {
        return new KkiruQuestion(1L, null, "000660", text);
    }

    private static UsableRiskGradeDTO grade(RiskDataStatus status) {
        return new UsableRiskGradeDTO(
                1L,
                "000660",
                "SK하이닉스",
                new BigDecimal("0.4005"),
                2,
                new BigDecimal("0.2220"),
                LocalDate.of(2023, 10, 27),
                LocalDate.of(2026, 10, 27),
                3L,
                status);
    }

    @Test
    void currentStockGradeIsExplainedWithoutExpiredMetrics() {
        org.mockito.Mockito.doReturn(
                        Optional.of(
                                new com.app.maria.domain.risk.dto.RiskGradeClassificationDTO(
                                        1L, "000660", "SK하이닉스", 2, "STOCK_RULE_CURRENT_STATUS")))
                .when(riskGradeService)
                .findClassificationByTicker("000660");
        when(riskGradeService.findUsableByTicker("000660")).thenReturn(Optional.empty());
        assertThat(tool.collect(question("위험등급")).get(0).text())
                .contains("2등급", "유효한 가격 지표가 없어", "별도로 산정")
                .doesNotContain("VaR은");
    }

    @Test
    @DisplayName("근거 한 문장: 등급 방향·VaR·변동성·기준 기간·자체 산정이 코드가 만든 글자로 들어간다")
    void sentence() {
        when(riskGradeService.findUsableByTicker("000660"))
                .thenReturn(Optional.of(grade(RiskDataStatus.CURRENT)));

        List<Evidence> evidence = tool.collect(question("위험등급이 몇이에요?"));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).id()).isEqualTo("risk:000660");
        assertThat(evidence.get(0).text())
                .isEqualTo(
                        "SK하이닉스의 위험등급은 2등급입니다(1등급 매우 높은 위험, 6등급 매우 낮은 위험). "
                                + "관측 기간의 일간수익률 기준 97.5% VaR은 40.1%, 연 변동성은 22.2%이며 "
                                + "기준 기간은 2023년 10월 27일부터 2026년 10월 27일까지입니다(자체 산정).");
        assertThat(evidence.get(0).url()).isNull();
    }

    @Test
    @DisplayName("기준일이 오래됐으면(STALE) 그 사실을 문장에 덧붙인다")
    void stale() {
        when(riskGradeService.findUsableByTicker("000660"))
                .thenReturn(Optional.of(grade(RiskDataStatus.STALE)));

        assertThat(tool.collect(question("위험등급")).get(0).text()).endsWith("기준 시점이 오래되었습니다.");
    }

    @Test
    @DisplayName("자료가 없으면 빈 결과(오류 아님)")
    void none() {
        when(riskGradeService.findUsableByTicker("000660")).thenReturn(Optional.empty());

        assertThat(tool.collect(question("위험등급"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"위험등급이 몇이에요?", "@끼루 변동성 큰가요", "안전한 종목인가요", "자꾸 흔들리네요", "등급 알려줘", "위 험 등 급"})
    @DisplayName("위험 관련 단어(공백·특수문자 무시)가 있으면 후보다")
    void relevant(String text) {
        assertThat(tool.isRelevant(question(text))).isTrue();
    }

    @Test
    @DisplayName("관련 단어가 없으면 후보가 아니다")
    void notRelevant() {
        assertThat(tool.isRelevant(question("배당 어땠어요?"))).isFalse();
    }
}
