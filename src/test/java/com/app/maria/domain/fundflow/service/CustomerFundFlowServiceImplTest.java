package com.app.maria.domain.fundflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.fundflow.dto.FundFlowCustomerReliefDTO;
import com.app.maria.domain.fundflow.dto.FundFlowCustomerSummaryDTO;
import com.app.maria.domain.fundflow.dto.FundFlowMonthDTO;
import com.app.maria.domain.fundflow.dto.FundFlowProductCostDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerFundFlowServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 0, 0);
    private static final LocalDate CLOCK_DATE = LocalDate.of(2026, 11, 18);
    private static final LocalDate AS_OF_DATE = LocalDate.of(2026, 11, 17);
    private static final Long CUSTOMER_ID = 7L;

    @Mock FundFlowMapper fundFlowMapper;

    @Mock BusinessClockService businessClockService;

    @InjectMocks CustomerFundFlowServiceImpl service;

    @BeforeEach
    void stubClock() {
        lenient().when(businessClockService.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("요약: 재투자율과 쉬운 문구를 서버가 만들어 준다")
    void summaryBuildsRatioAndMessage() {
        when(fundFlowMapper.selectCustomerSummary(CUSTOMER_ID, CLOCK_DATE))
                .thenReturn(
                        FundFlowCustomerSummaryDTO.builder()
                                .hasAccount(true)
                                .soldForeignAmount(new BigDecimal("15000"))
                                .krwInflowAmount(new BigDecimal("14000"))
                                .domesticBuyAmount(new BigDecimal("7000"))
                                .cashBalance(new BigDecimal("12345"))
                                .build());

        FundFlowCustomerSummaryResponseDTO result = service.getSummary(CUSTOMER_ID);

        assertThat(result.getAsOfDate()).isEqualTo(AS_OF_DATE);
        assertThat(result.isHasAccount()).isTrue();
        assertThat(result.getReinvestRatio()).isEqualByComparingTo("0.5000");
        assertThat(result.getMessage()).isEqualTo("해외주식을 판 돈의 50%를 다시 국내에 투자했어요");
    }

    @Test
    @DisplayName("요약: 확정 유입이 0이면 비율은 null이고 안내 문구를 준다")
    void summaryWithoutInflow() {
        when(fundFlowMapper.selectCustomerSummary(CUSTOMER_ID, CLOCK_DATE))
                .thenReturn(
                        FundFlowCustomerSummaryDTO.builder()
                                .hasAccount(true)
                                .soldForeignAmount(BigDecimal.ZERO)
                                .krwInflowAmount(BigDecimal.ZERO)
                                .domesticBuyAmount(BigDecimal.ZERO)
                                .cashBalance(BigDecimal.ZERO)
                                .build());

        FundFlowCustomerSummaryResponseDTO result = service.getSummary(CUSTOMER_ID);

        assertThat(result.getReinvestRatio()).isNull();
        assertThat(result.getMessage()).isEqualTo("아직 원화로 확정된 금액이 없어요");
    }

    @Test
    @DisplayName("요약: 계좌가 없으면 404가 아니라 hasAccount=false로 응답한다")
    void summaryWithoutAccount() {
        when(fundFlowMapper.selectCustomerSummary(CUSTOMER_ID, CLOCK_DATE)).thenReturn(null);

        FundFlowCustomerSummaryResponseDTO result = service.getSummary(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isFalse();
        assertThat(result.getAsOfDate()).isEqualTo(AS_OF_DATE);
        assertThat(result.getMessage()).isEqualTo("아직 RIA 계좌가 없어요");
    }

    @Test
    @DisplayName("월별: 완료된 달 뒤에 진행 중인 달을 partial=true 로 붙이고 기준일을 함께 내린다")
    void monthlyAppendsPartialMonthMidMonth() {
        LocalDate monthStart = LocalDate.of(2026, 11, 1);
        when(fundFlowMapper.selectCustomerMonthly(CUSTOMER_ID, monthStart))
                .thenReturn(
                        List.of(
                                FundFlowMonthDTO.builder()
                                        .flowMonth(LocalDate.of(2026, 10, 1))
                                        .build()));
        when(fundFlowMapper.selectCustomerMonthlyPartial(CUSTOMER_ID, monthStart, CLOCK_DATE))
                .thenReturn(
                        FundFlowMonthDTO.builder()
                                .domesticBuyAmount(new BigDecimal("500"))
                                .build());

        FundFlowMonthlyResponseDTO result = service.getMonthly(CUSTOMER_ID);

        assertThat(result.getAsOfDate()).isEqualTo(AS_OF_DATE);
        assertThat(result.getMonths()).hasSize(2);
        assertThat(result.getMonths().get(0).isPartial()).isFalse();
        assertThat(result.getMonths().get(1).isPartial()).isTrue();
        assertThat(result.getMonths().get(1).getFlowMonth()).isEqualTo(monthStart);
    }

    @Test
    @DisplayName("월별: 월초(업무시계가 1일)에는 진행 중인 달을 만들지 않는다")
    void monthlyOmitsPartialMonthOnFirstDay() {
        when(businessClockService.now()).thenReturn(LocalDateTime.of(2026, 11, 1, 0, 0));
        when(fundFlowMapper.selectCustomerMonthly(CUSTOMER_ID, LocalDate.of(2026, 11, 1)))
                .thenReturn(List.of());

        FundFlowMonthlyResponseDTO result = service.getMonthly(CUSTOMER_ID);

        assertThat(result.getMonths()).isEmpty();
        verify(fundFlowMapper, never()).selectCustomerMonthlyPartial(any(), any(), any());
    }

    @Test
    @DisplayName("월별: 개설된 계좌가 없으면 진행 중인 달도 없다")
    void monthlyHasNoPartialMonthWithoutAccount() {
        when(fundFlowMapper.selectCustomerMonthly(CUSTOMER_ID, LocalDate.of(2026, 11, 1)))
                .thenReturn(List.of());
        when(fundFlowMapper.selectCustomerMonthlyPartial(any(), any(), any())).thenReturn(null);

        assertThat(service.getMonthly(CUSTOMER_ID).getMonths()).isEmpty();
    }

    @Test
    @DisplayName("혜택: 스냅샷으로 분류 코드·쉬운 문구·감면액을 채운다")
    void reliefClassifiesAndComputesReliefAmount() {
        when(fundFlowMapper.selectCustomerRelief(CUSTOMER_ID, AS_OF_DATE))
                .thenReturn(
                        FundFlowCustomerReliefDTO.builder()
                                .hasAccount(true)
                                .benefit("REDUCED")
                                .weightedSell(new BigDecimal("100"))
                                .adjustRatio(new BigDecimal("0.5000"))
                                .finalDeduction(new BigDecimal("10"))
                                .finalTax(new BigDecimal("1000000"))
                                .generalTax(new BigDecimal("2200000"))
                                .build());

        FundFlowCustomerReliefResponseDTO result = service.getRelief(CUSTOMER_ID);

        assertThat(result.getStatusCode()).isEqualTo("PARTIAL_REDUCED");
        assertThat(result.getStatusLabel()).isEqualTo("다른 계좌의 해외주식 매수로 혜택이 일부 줄었어요");
        assertThat(result.getReliefAmount()).isEqualByComparingTo("1200000");
        assertThat(result.getNotice()).isNotBlank();
    }

    @Test
    @DisplayName("혜택: 해지 등으로 혜택이 취소된 계좌는 BENEFIT_CANCELED가 우선한다")
    void reliefCanceledBenefitWinsOverAdjustRatio() {
        when(fundFlowMapper.selectCustomerRelief(CUSTOMER_ID, AS_OF_DATE))
                .thenReturn(
                        FundFlowCustomerReliefDTO.builder()
                                .hasAccount(true)
                                .benefit("IMPOSSIBLE")
                                .weightedSell(new BigDecimal("100"))
                                .adjustRatio(BigDecimal.ONE)
                                .finalTax(BigDecimal.ZERO)
                                .generalTax(BigDecimal.ZERO)
                                .build());

        assertThat(service.getRelief(CUSTOMER_ID).getStatusCode()).isEqualTo("BENEFIT_CANCELED");
    }

    @Test
    @DisplayName("혜택: 스냅샷이 아직 없으면 분류 없이 안내 문구만 준다")
    void reliefWithoutSnapshot() {
        when(fundFlowMapper.selectCustomerRelief(CUSTOMER_ID, AS_OF_DATE))
                .thenReturn(FundFlowCustomerReliefDTO.builder().hasAccount(true).build());

        FundFlowCustomerReliefResponseDTO result = service.getRelief(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isTrue();
        assertThat(result.getStatusCode()).isNull();
        assertThat(result.getStatusLabel()).isEqualTo("아직 계산된 예상 혜택이 없어요");
        assertThat(result.getReliefAmount()).isNull();
    }

    @Test
    @DisplayName("운용처: 매입금액을 주식·ETF펀드·예수금으로 나누고 업종 상위 3만 준다")
    void allocationSplitsBucketsAndTopSectors() {
        when(fundFlowMapper.selectCustomerSummary(CUSTOMER_ID, CLOCK_DATE))
                .thenReturn(
                        FundFlowCustomerSummaryDTO.builder()
                                .hasAccount(true)
                                .cashBalance(new BigDecimal("1000"))
                                .build());
        when(fundFlowMapper.selectCustomerProductCosts(CUSTOMER_ID))
                .thenReturn(
                        List.of(
                                product("A", "STOCK", "반도체", "4000"),
                                product("B", "STOCK", "반도체", "1000"),
                                product("C", "STOCK", "금융", "2000"),
                                product("D", "STOCK", null, "500"),
                                product("E", "STOCK", "자동차", "300"),
                                product("F", "FUND", null, "1200")));

        FundFlowCustomerAllocationResponseDTO result = service.getAllocation(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isTrue();
        assertThat(result.getHoldingsCost()).isEqualByComparingTo("9000");
        assertThat(result.getTotalValue()).isEqualByComparingTo("10000");
        assertThat(result.getBuckets())
                .extracting(FundFlowCustomerAllocationResponseDTO.Bucket::getLabel)
                .containsExactly("국내 주식", "ETF·펀드", "예수금");
        assertThat(result.getBuckets().get(0).getShare()).isEqualByComparingTo("0.7800");
        assertThat(result.getBuckets().get(2).getShare()).isEqualByComparingTo("0.1000");
        assertThat(result.getSectors())
                .extracting(FundFlowCustomerAllocationResponseDTO.Bucket::getLabel)
                .containsExactly("반도체", "금융", "기타");
        assertThat(result.getSectors().get(0).getShare()).isEqualByComparingTo("0.5556");
    }

    @Test
    @DisplayName("운용처: 계좌가 없으면 hasAccount=false로 응답한다")
    void allocationWithoutAccount() {
        when(fundFlowMapper.selectCustomerSummary(CUSTOMER_ID, CLOCK_DATE)).thenReturn(null);

        FundFlowCustomerAllocationResponseDTO result = service.getAllocation(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isFalse();
        assertThat(result.getBuckets()).isEmpty();
    }

    private static FundFlowProductCostDTO product(
            String name, String type, String sector, String cost) {
        return FundFlowProductCostDTO.builder()
                .name(name)
                .type(type)
                .sectorName(sector)
                .purchaseCost(new BigDecimal(cost))
                .build();
    }

    @Test
    @DisplayName("혜택: 계좌가 없으면 hasAccount=false로 응답한다")
    void reliefWithoutAccount() {
        when(fundFlowMapper.selectCustomerRelief(CUSTOMER_ID, AS_OF_DATE)).thenReturn(null);

        FundFlowCustomerReliefResponseDTO result = service.getRelief(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isFalse();
        assertThat(result.getAsOfDate()).isEqualTo(AS_OF_DATE);
    }
}
