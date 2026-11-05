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
import com.app.maria.domain.fundflow.dto.FundFlowTransactionRowDTO;
import com.app.maria.domain.fundflow.dto.FundFlowWithdrawalLotRowDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerAllocationResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerReliefResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowCustomerSummaryResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowMonthlyResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowTransactionsResponseDTO;
import com.app.maria.domain.fundflow.dto.response.FundFlowWithdrawalScheduleResponseDTO;
import com.app.maria.domain.fundflow.mapper.FundFlowMapper;
import com.app.maria.domain.fundflow.type.FundFlowTransactionType;
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

    private static FundFlowTransactionRowDTO row(
            String date,
            FundFlowTransactionType type,
            Long productId,
            String name,
            String qty,
            String amount,
            String fee,
            String cash) {
        return FundFlowTransactionRowDTO.builder()
                .eventDate(LocalDate.parse(date))
                .type(type)
                .domesticProductId(productId)
                .productName(name)
                .qty(qty == null ? null : new BigDecimal(qty))
                .amount(new BigDecimal(amount))
                .feeAmount(new BigDecimal(fee))
                .taxAmount(BigDecimal.ZERO)
                .cashAmount(new BigDecimal(cash))
                .build();
    }

    @Test
    @DisplayName("거래 내역: 같은 날은 원화 확정 → 매도 → 매수 → 인출 순으로 처리하고 쪼개진 원화 확정과 종목별 매수를 합친다")
    void transactionsMergeAndOrderWithinSameDay() {
        when(fundFlowMapper.existsCustomerOpenedAccount(CUSTOMER_ID)).thenReturn(true);
        // 일부러 뒤섞은 순서로 준다
        when(fundFlowMapper.selectCustomerTransactionRows(CUSTOMER_ID, CLOCK_DATE))
                .thenReturn(
                        List.of(
                                row(
                                        "2026-10-23",
                                        FundFlowTransactionType.WITHDRAWAL,
                                        null,
                                        null,
                                        null,
                                        "1000",
                                        "0",
                                        "-1000"),
                                row(
                                        "2026-10-23",
                                        FundFlowTransactionType.DOMESTIC_BUY,
                                        10L,
                                        "삼성전자",
                                        "3",
                                        "300",
                                        "5",
                                        "-305"),
                                row(
                                        "2026-10-23",
                                        FundFlowTransactionType.KRW_CONFIRMED,
                                        null,
                                        null,
                                        null,
                                        "600",
                                        "0",
                                        "600"),
                                row(
                                        "2026-10-23",
                                        FundFlowTransactionType.DOMESTIC_BUY,
                                        11L,
                                        "KODEX 200",
                                        "1",
                                        "9000",
                                        "5",
                                        "-9005"),
                                row(
                                        "2026-10-23",
                                        FundFlowTransactionType.KRW_CONFIRMED,
                                        null,
                                        null,
                                        null,
                                        "10000",
                                        "0",
                                        "10000"),
                                row(
                                        "2026-10-23",
                                        FundFlowTransactionType.DOMESTIC_BUY,
                                        10L,
                                        "삼성전자",
                                        "2",
                                        "200",
                                        "5",
                                        "-205"),
                                row(
                                        "2026-10-23",
                                        FundFlowTransactionType.DOMESTIC_SELL,
                                        12L,
                                        "NAVER",
                                        "1",
                                        "500",
                                        "5",
                                        "490"),
                                row(
                                        "2026-10-22",
                                        FundFlowTransactionType.KRW_CONFIRMED,
                                        null,
                                        null,
                                        null,
                                        "100",
                                        "0",
                                        "100")));

        FundFlowTransactionsResponseDTO result = service.getTransactions(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isTrue();
        assertThat(result.getAsOfDate()).isEqualTo(AS_OF_DATE);
        assertThat(result.getItems())
                .extracting(FundFlowTransactionsResponseDTO.Item::getType)
                .containsExactly(
                        FundFlowTransactionType.KRW_CONFIRMED, // 10/22
                        FundFlowTransactionType.KRW_CONFIRMED, // 10/23
                        FundFlowTransactionType.DOMESTIC_SELL,
                        FundFlowTransactionType.DOMESTIC_BUY,
                        FundFlowTransactionType.WITHDRAWAL);
        assertThat(result.getItems())
                .extracting(FundFlowTransactionsResponseDTO.Item::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("100"),
                        new BigDecimal("10600"), // 쪼개진 원화 확정 합계
                        new BigDecimal("490"),
                        new BigDecimal("-9515"), // -305 + -9005 + -205
                        new BigDecimal("-1000"));
        // 예수금 = 100 → 10700 → 11190 → 1675 → 675
        assertThat(result.getItems())
                .extracting(FundFlowTransactionsResponseDTO.Item::getCashAfter)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("100"),
                        new BigDecimal("10700"),
                        new BigDecimal("11190"),
                        new BigDecimal("1675"),
                        new BigDecimal("675"));

        FundFlowTransactionsResponseDTO.Item buy = result.getItems().get(3);
        assertThat(buy.getTitle()).isEqualTo("KODEX 200 외 1"); // 금액이 큰 종목이 대표
        assertThat(buy.getDetails()).hasSize(2);
        assertThat(buy.getDetails().get(1).getName()).isEqualTo("삼성전자");
        assertThat(buy.getDetails().get(1).getQty()).isEqualByComparingTo("5"); // 같은 종목 두 건 합침
        assertThat(buy.getDetails().get(1).getAmount()).isEqualByComparingTo("500");
        assertThat(buy.getDetails().get(1).getFeeAmount()).isEqualByComparingTo("10");

        FundFlowTransactionsResponseDTO.Item sell = result.getItems().get(2);
        assertThat(sell.getTitle()).isEqualTo("NAVER"); // 종목이 하나면 종목명만
        assertThat(result.getItems().get(1).getTitle()).isNull();
        assertThat(result.getItems().get(1).getDetails()).isEmpty();
    }

    @Test
    @DisplayName("거래 내역: 패키지 밖 체결도 mapper가 준 행이면 그대로 합산에 들어간다(거르지 않는다)")
    void transactionsKeepRowsRegardlessOfOrigin() {
        when(fundFlowMapper.existsCustomerOpenedAccount(CUSTOMER_ID)).thenReturn(true);
        when(fundFlowMapper.selectCustomerTransactionRows(CUSTOMER_ID, CLOCK_DATE))
                .thenReturn(
                        List.of(
                                row(
                                        "2026-05-19",
                                        FundFlowTransactionType.KRW_CONFIRMED,
                                        null,
                                        null,
                                        null,
                                        "1000",
                                        "0",
                                        "1000"),
                                row(
                                        "2026-05-22",
                                        FundFlowTransactionType.DOMESTIC_BUY,
                                        10L,
                                        "삼성전자",
                                        "1",
                                        "300",
                                        "5",
                                        "-305"),
                                row(
                                        "2026-09-04",
                                        FundFlowTransactionType.DOMESTIC_BUY,
                                        11L,
                                        "KODEX 200",
                                        "1",
                                        "200",
                                        "5",
                                        "-205")));

        FundFlowTransactionsResponseDTO result = service.getTransactions(CUSTOMER_ID);

        assertThat(result.getItems()).hasSize(3);
        assertThat(result.getItems().get(2).getCashAfter()).isEqualByComparingTo("490");
    }

    @Test
    @DisplayName("거래 내역: 계좌는 있는데 거래가 없으면 빈 목록이다")
    void transactionsEmptyWhenNoRows() {
        when(fundFlowMapper.existsCustomerOpenedAccount(CUSTOMER_ID)).thenReturn(true);
        when(fundFlowMapper.selectCustomerTransactionRows(CUSTOMER_ID, CLOCK_DATE))
                .thenReturn(List.of());

        FundFlowTransactionsResponseDTO result = service.getTransactions(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isTrue();
        assertThat(result.getItems()).isEmpty();
    }

    @Test
    @DisplayName("거래 내역: 개설된 계좌가 없으면 hasAccount=false이고 원천 행은 조회하지 않는다")
    void transactionsWithoutAccount() {
        when(fundFlowMapper.existsCustomerOpenedAccount(CUSTOMER_ID)).thenReturn(false);

        FundFlowTransactionsResponseDTO result = service.getTransactions(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isFalse();
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getAsOfDate()).isEqualTo(AS_OF_DATE);
        verify(fundFlowMapper, never()).selectCustomerTransactionRows(any(), any());
    }

    private static FundFlowTransactionRowDTO trendRow(
            String dateTime,
            FundFlowTransactionType type,
            Long productId,
            String qty,
            String price,
            String amount,
            String cash) {
        LocalDateTime at = LocalDateTime.parse(dateTime);
        return FundFlowTransactionRowDTO.builder()
                .eventDate(at.toLocalDate())
                .eventAt(at)
                .type(type)
                .domesticProductId(productId)
                .productName(productId == null ? null : "종목" + productId)
                .qty(qty == null ? null : new BigDecimal(qty))
                .price(price == null ? null : new BigDecimal(price))
                .amount(new BigDecimal(amount))
                .feeAmount(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .cashAmount(new BigDecimal(cash))
                .build();
    }

    private static FundFlowWithdrawalLotRowDTO lotRow(
            long exchangeId, String finalAt, String paid, String remaining, String withdrawn) {
        return FundFlowWithdrawalLotRowDTO.builder()
                .exchangeId(exchangeId)
                .finalAt(LocalDateTime.parse(finalAt))
                .paidAmount(new BigDecimal(paid))
                .remainingAmount(new BigDecimal(remaining))
                .withdrawnAmount(new BigDecimal(withdrawn))
                .build();
    }

    @Test
    @DisplayName("인출 일정: 같은 납입일은 합치고 인출 가능일은 납입일 + 1년, 만기 당일 0시는 인출 가능으로 본다")
    void withdrawalScheduleGroupsByPaidDate() {
        when(fundFlowMapper.existsCustomerOpenedAccount(CUSTOMER_ID)).thenReturn(true);
        when(fundFlowMapper.selectCustomerWithdrawalLots(CUSTOMER_ID))
                .thenReturn(
                        List.of(
                                lotRow(1, "2025-11-18T00:00:00", "7000000", "0", "7000000"),
                                lotRow(2, "2026-04-28T00:00:00", "30000000", "28000000", "2000000"),
                                lotRow(3, "2026-04-28T00:00:00", "10000000", "10000000", "0"),
                                lotRow(4, "2026-06-08T00:00:00", "5000000", "5000000", "0")));

        FundFlowWithdrawalScheduleResponseDTO result = service.getWithdrawalSchedule(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isTrue();
        assertThat(result.getBusinessDate()).isEqualTo(CLOCK_DATE);
        assertThat(result.getLots()).hasSize(3);
        var first = result.getLots().get(0);
        assertThat(first.getPaidDate()).isEqualTo(LocalDate.of(2025, 11, 18));
        assertThat(first.getMaturityDate()).isEqualTo(LocalDate.of(2026, 11, 18));
        assertThat(first.isMatured()).isTrue(); // 인출 처리와 같은 판정: 만기 시각이 지금과 같으면 인출 가능
        assertThat(first.getRemainingAmount()).isEqualByComparingTo("0"); // 전량 인출된 건도 나온다
        var merged = result.getLots().get(1);
        assertThat(merged.getPaidAmount()).isEqualByComparingTo("40000000");
        assertThat(merged.getWithdrawnAmount()).isEqualByComparingTo("2000000");
        assertThat(merged.getRemainingAmount()).isEqualByComparingTo("38000000");
        assertThat(merged.getMaturityDate()).isEqualTo(LocalDate.of(2027, 4, 28));
        assertThat(merged.isMatured()).isFalse();
    }

    @Test
    @DisplayName("인출 일정: 개설된 계좌가 없으면 hasAccount=false이고 납입건은 조회하지 않는다")
    void withdrawalScheduleWithoutAccount() {
        when(fundFlowMapper.existsCustomerOpenedAccount(CUSTOMER_ID)).thenReturn(false);

        FundFlowWithdrawalScheduleResponseDTO result = service.getWithdrawalSchedule(CUSTOMER_ID);

        assertThat(result.isHasAccount()).isFalse();
        assertThat(result.getLots()).isEmpty();
        verify(fundFlowMapper, never()).selectCustomerWithdrawalLots(any());
    }
}
