package com.app.maria.domain.targetproduct.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.externaltradesync.dto.response.MydataTradeResponseDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailureListDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementFailurePageDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementListDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductJudgementPageDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductSearchDTO;
import com.app.maria.domain.targetproduct.dto.TargetProductSummaryDTO;
import com.app.maria.domain.targetproduct.dto.request.TargetProductSearchRequestDTO;
import com.app.maria.domain.targetproduct.dto.response.MydataFundResponseDTO;
import com.app.maria.domain.targetproduct.mapper.TargetProductMapper;
import com.app.maria.domain.targetproduct.type.StockType;
import com.app.maria.domain.targetproduct.type.TradeType;
import com.app.maria.global.client.mydatafund.MydataFundClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExternalTradeSyncProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TargetProductServiceImplTest {

    @Mock private TargetProductMapper targetProductMapper;

    @Mock private MydataFundClient mydataFundClient;

    @Mock private BusinessClockService businessClockService;

    @Mock private ExternalTradeSyncProperties externalTradeSyncProperties;

    @InjectMocks private TargetProductServiceImpl targetProductService;

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 7, 0, 0);

    @BeforeEach
    void setUp() {
        lenient().when(businessClockService.now()).thenReturn(FIXED_NOW);
        // ExternalTradeSyncServiceImplTest와 동일한 이유로, 스텁 안 하면 int 기본값 0이
        // 반환되어 getPermanentFailures()의 cutoff 계산이 깨진다.
        lenient().when(externalTradeSyncProperties.getMaxJudgeFailureDays()).thenReturn(3);
    }

    @Test
    @DisplayName(
            "getJudgements()는 page*size를 offset으로 계산한 TargetProductSearchDTO를 매퍼에 넘기고 결과를 그대로 담는다")
    void getJudgementsCalculatesOffsetAndDelegatesToMapper() {
        List<TargetProductJudgementListDTO> expected =
                List.of(TargetProductJudgementListDTO.builder().judgementId(1L).build());
        ArgumentCaptor<TargetProductSearchDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchDTO.class);
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(expected);
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(25);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder().page(2).size(10).build();
        TargetProductJudgementPageDTO result = targetProductService.getJudgements(request);

        verify(targetProductMapper).selectJudgements(captor.capture());
        assertThat(captor.getValue().getOffset()).isEqualTo(20);
        assertThat(captor.getValue().getSize()).isEqualTo(10);
        assertThat(result.getContent()).isEqualTo(expected);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("getJudgements()는 totalElements를 size로 나눈 뒤 올림해 totalPages를 계산한다")
    void getJudgementsCalculatesTotalPagesFromTotalElements() {
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(List.of());
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(45);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder().page(0).size(20).build();
        TargetProductJudgementPageDTO result = targetProductService.getJudgements(request);

        assertThat(result.getTotalElements()).isEqualTo(45);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("getJudgements()는 조회 결과가 없으면 totalPages를 0으로 계산한다")
    void getJudgementsReturnsZeroTotalPagesWhenNoRows() {
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(List.of());
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(0);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder().page(0).size(20).build();
        TargetProductJudgementPageDTO result = targetProductService.getJudgements(request);

        assertThat(result.getTotalElements()).isEqualTo(0);
        assertThat(result.getTotalPages()).isEqualTo(0);
    }

    @Test
    @DisplayName(
            "getJudgements()는 필터 조건(customerName/stockType/isTarget)을 TargetProductSearchDTO에 그대로 전달한다")
    void getJudgementsPassesFilterConditionsToSearchDTO() {
        ArgumentCaptor<TargetProductSearchDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchDTO.class);
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(List.of());
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(0);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder()
                        .customerName("홍길동")
                        .stockType(StockType.ETF)
                        .isTarget(true)
                        .page(0)
                        .size(20)
                        .build();
        targetProductService.getJudgements(request);

        verify(targetProductMapper).selectJudgements(captor.capture());
        assertThat(captor.getValue().getCustomerName()).isEqualTo("홍길동");
        assertThat(captor.getValue().getStockType()).isEqualTo(StockType.ETF);
        assertThat(captor.getValue().getIsTarget()).isTrue();
    }

    @Test
    @DisplayName("getJudgements()는 tradeType 필터를 TargetProductSearchDTO에 그대로 전달한다")
    void getJudgementsPassesTradeTypeToSearchDTO() {
        ArgumentCaptor<TargetProductSearchDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchDTO.class);
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(List.of());
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(0);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder()
                        .tradeType(TradeType.INHERITANCE)
                        .page(0)
                        .size(20)
                        .build();
        targetProductService.getJudgements(request);

        verify(targetProductMapper).selectJudgements(captor.capture());
        assertThat(captor.getValue().getTradeType()).isEqualTo(TradeType.INHERITANCE);
    }

    @Test
    @DisplayName("getJudgements()는 inheritanceGiftOnly 필터를 TargetProductSearchDTO에 그대로 전달한다")
    void getJudgementsPassesInheritanceGiftOnlyToSearchDTO() {
        ArgumentCaptor<TargetProductSearchDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchDTO.class);
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(List.of());
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(0);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder()
                        .inheritanceGiftOnly(true)
                        .page(0)
                        .size(20)
                        .build();
        targetProductService.getJudgements(request);

        verify(targetProductMapper).selectJudgements(captor.capture());
        assertThat(captor.getValue().getInheritanceGiftOnly()).isTrue();
    }

    @Test
    @DisplayName(
            "getJudgements()는 todayOnly가 true면 BusinessClockService의 오늘~내일 범위를 judgedAtFrom/judgedAtTo로 채운다")
    void getJudgementsResolvesTodayOnlyToJudgedAtRangeUsingClock() {
        ArgumentCaptor<TargetProductSearchDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchDTO.class);
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(List.of());
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(0);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder().todayOnly(true).page(0).size(20).build();
        targetProductService.getJudgements(request);

        verify(targetProductMapper).selectJudgements(captor.capture());
        assertThat(captor.getValue().getJudgedAtFrom())
                .isEqualTo(FIXED_NOW.toLocalDate().atStartOfDay());
        assertThat(captor.getValue().getJudgedAtTo())
                .isEqualTo(FIXED_NOW.toLocalDate().plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("getJudgements()는 todayOnly가 없으면 judgedAtFrom/judgedAtTo를 채우지 않는다")
    void getJudgementsLeavesJudgedAtRangeNullWhenTodayOnlyNotSet() {
        ArgumentCaptor<TargetProductSearchDTO> captor =
                ArgumentCaptor.forClass(TargetProductSearchDTO.class);
        when(targetProductMapper.selectJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(List.of());
        when(targetProductMapper.countFilteredJudgements(any(TargetProductSearchDTO.class)))
                .thenReturn(0);

        TargetProductSearchRequestDTO request =
                TargetProductSearchRequestDTO.builder().page(0).size(20).build();
        targetProductService.getJudgements(request);

        verify(targetProductMapper).selectJudgements(captor.capture());
        assertThat(captor.getValue().getJudgedAtFrom()).isNull();
        assertThat(captor.getValue().getJudgedAtTo()).isNull();
    }

    @Test
    @DisplayName("getSummary()는 BusinessClockService의 오늘~내일 범위로 매퍼를 조회하고 결과를 그대로 담는다")
    void getSummaryDelegatesToMapperUsingClockToday() {
        LocalDate today = FIXED_NOW.toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        TargetProductSummaryDTO todayStats =
                TargetProductSummaryDTO.builder()
                        .todayJudgementCount(3)
                        .todayTargetCount(2)
                        .todayTargetNetBuyAmount(new BigDecimal("1500000.00"))
                        .todayInheritanceGiftCount(1)
                        .build();
        when(targetProductMapper.selectSummary(today, tomorrow)).thenReturn(todayStats);
        when(targetProductMapper.countJudgements()).thenReturn(50);

        TargetProductSummaryDTO result = targetProductService.getSummary();

        verify(targetProductMapper).selectSummary(today, tomorrow);
        assertThat(result.getTodayJudgementCount()).isEqualTo(3);
        assertThat(result.getTodayTargetCount()).isEqualTo(2);
        assertThat(result.getTodayTargetNetBuyAmount()).isEqualByComparingTo("1500000.00");
        assertThat(result.getTodayInheritanceGiftCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getSummary()의 totalJudgementCount는 selectSummary가 아니라 countJudgements() 결과로 채워진다")
    void getSummarySetsTotalJudgementCountFromCountJudgements() {
        LocalDate today = FIXED_NOW.toLocalDate();
        TargetProductSummaryDTO todayStats =
                TargetProductSummaryDTO.builder()
                        .todayJudgementCount(0)
                        .todayTargetCount(0)
                        .todayTargetNetBuyAmount(BigDecimal.ZERO)
                        .build();
        when(targetProductMapper.selectSummary(today, today.plusDays(1))).thenReturn(todayStats);
        when(targetProductMapper.countJudgements()).thenReturn(50);

        TargetProductSummaryDTO result = targetProductService.getSummary();

        assertThat(result.getTotalJudgementCount()).isEqualTo(50);
    }

    @Test
    @DisplayName(
            "getPermanentFailures()는 page*size를 offset으로, now()-maxJudgeFailureDays를 cutoff로 계산해 매퍼에 넘긴다")
    void getPermanentFailuresCalculatesOffsetAndCutoffAndDelegatesToMapper() {
        List<TargetProductJudgementFailureListDTO> expected =
                List.of(TargetProductJudgementFailureListDTO.builder().failureId(1L).build());
        when(targetProductMapper.selectPermanentFailures(any(), eq(20), eq(10)))
                .thenReturn(expected);
        when(targetProductMapper.countPermanentFailures(any())).thenReturn(25);

        TargetProductJudgementFailurePageDTO result =
                targetProductService.getPermanentFailures(2, 10);

        verify(targetProductMapper)
                .selectPermanentFailures(eq(FIXED_NOW.minusDays(3)), eq(20), eq(10));
        assertThat(result.getContent()).isEqualTo(expected);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("getPermanentFailures()는 totalElements를 size로 나눈 뒤 올림해 totalPages를 계산한다")
    void getPermanentFailuresCalculatesTotalPagesFromTotalElements() {
        when(targetProductMapper.selectPermanentFailures(any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(targetProductMapper.countPermanentFailures(any())).thenReturn(45);

        TargetProductJudgementFailurePageDTO result =
                targetProductService.getPermanentFailures(0, 20);

        assertThat(result.getTotalElements()).isEqualTo(45);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    private static MydataTradeResponseDTO trade(
            Long tradeId,
            String tradeType,
            String stockType,
            String fundCode,
            BigDecimal amount,
            LocalDate tradeDate) {
        return MydataTradeResponseDTO.builder()
                .tradeId(tradeId)
                .ciHash("ci-1")
                .brokerName("증권사A")
                .tradeType(tradeType)
                .stockType(stockType)
                .qty(BigDecimal.TEN)
                .tradeDate(tradeDate)
                .amount(amount)
                .fundCode(fundCode)
                .ticker("AAPL")
                .build();
    }

    @Test
    @DisplayName("FOREIGN_STOCK은 비중요건 없이 대상상품으로 판정하고 mydata를 조회하지 않는다")
    void judgeMarksForeignStockAsTargetWithoutFundLookup() {
        MydataTradeResponseDTO t =
                trade(
                        1L,
                        "BUY",
                        "FOREIGN_STOCK",
                        null,
                        BigDecimal.valueOf(1_000_000),
                        LocalDate.of(2026, 3, 5));

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getIsTarget()).isTrue();
        assertThat(result.getFundCode()).isNull();
        verifyNoInteractions(mydataFundClient);
        verify(targetProductMapper).insertJudgement(result);
    }

    @Test
    @DisplayName("ETF는 비중요건 없이 대상상품으로 판정한다")
    void judgeMarksEtfAsTarget() {
        MydataTradeResponseDTO t =
                trade(
                        2L,
                        "BUY",
                        "ETF",
                        null,
                        BigDecimal.valueOf(500_000),
                        LocalDate.of(2026, 3, 6));

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getIsTarget()).isTrue();
        verifyNoInteractions(mydataFundClient);
    }

    @Test
    @DisplayName("FUND는 해외비중 60% 이상 + 설정 1개월 경과를 모두 만족해야 대상상품으로 판정한다")
    void judgeMarksFundAsTargetWhenBothConditionsMet() {
        LocalDate tradeDate = LocalDate.of(2026, 3, 10);
        MydataFundResponseDTO fund =
                MydataFundResponseDTO.builder()
                        .fundCode("448630")
                        .fundName("TIGER 미국배당다우존스")
                        .foreignStockRatio(BigDecimal.valueOf(72.50))
                        .inceptionDate(tradeDate.minusMonths(2))
                        .build();
        when(mydataFundClient.getFund("448630")).thenReturn(fund);
        MydataTradeResponseDTO t =
                trade(3L, "BUY", "FUND", "448630", BigDecimal.valueOf(1_000_000), tradeDate);

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getIsTarget()).isTrue();
        assertThat(result.getForeignStockRatio()).isEqualByComparingTo("72.50");
        assertThat(result.getInceptionDate()).isEqualTo(fund.getInceptionDate());
        assertThat(result.getFundName()).isEqualTo(fund.getFundName());
    }

    @Test
    @DisplayName("FUND의 해외비중이 60% 미만이면 대상상품이 아니다")
    void judgeMarksFundAsNonTargetWhenRatioBelowThreshold() {
        LocalDate tradeDate = LocalDate.of(2026, 3, 10);
        MydataFundResponseDTO fund =
                MydataFundResponseDTO.builder()
                        .fundCode("069500")
                        .foreignStockRatio(BigDecimal.valueOf(59.99))
                        .inceptionDate(tradeDate.minusMonths(2))
                        .build();
        when(mydataFundClient.getFund("069500")).thenReturn(fund);
        MydataTradeResponseDTO t =
                trade(4L, "BUY", "FUND", "069500", BigDecimal.valueOf(1_000_000), tradeDate);

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getIsTarget()).isFalse();
    }

    @Test
    @DisplayName("FUND의 설정일이 1개월 미경과이면 비중이 충분해도 대상상품이 아니다")
    void judgeMarksFundAsNonTargetWhenInceptionPeriodNotMet() {
        LocalDate tradeDate = LocalDate.of(2026, 3, 10);
        MydataFundResponseDTO fund =
                MydataFundResponseDTO.builder()
                        .fundCode("381170")
                        .foreignStockRatio(BigDecimal.valueOf(88.00))
                        .inceptionDate(tradeDate.minusDays(10))
                        .build();
        when(mydataFundClient.getFund("381170")).thenReturn(fund);
        MydataTradeResponseDTO t =
                trade(5L, "BUY", "FUND", "381170", BigDecimal.valueOf(1_000_000), tradeDate);

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getIsTarget()).isFalse();
    }

    @Test
    @DisplayName("설정일이 정확히 1개월 경과한 경계값은 요건을 충족한다")
    void judgeTreatsExactlyOneMonthAsMet() {
        LocalDate tradeDate = LocalDate.of(2026, 3, 10);
        MydataFundResponseDTO fund =
                MydataFundResponseDTO.builder()
                        .fundCode("448630")
                        .foreignStockRatio(BigDecimal.valueOf(60.00))
                        .inceptionDate(tradeDate.minusMonths(1))
                        .build();
        when(mydataFundClient.getFund("448630")).thenReturn(fund);
        MydataTradeResponseDTO t =
                trade(6L, "BUY", "FUND", "448630", BigDecimal.valueOf(1_000_000), tradeDate);

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getIsTarget()).isTrue();
    }

    @Test
    @DisplayName("설정 1개월 경과 판정은 판정 실행 시각이 아니라 거래일(tradeDate) 기준이다")
    void judgeUsesTradeDateNotJudgementTimeForInceptionPeriodCheck() {
        // 설정일 2026-03-05, 거래일 2026-03-10 -> 거래일 기준으론 5일만 지나 미충족.
        // FIXED_NOW(2026-08-07) 기준으로는 5개월 넘게 지나 있어서, 판정 시각을 기준으로
        // 삼았다면 충족으로 오판정됐을 시나리오 (캐치업 동기화로 오래된 거래를 뒤늦게
        // 판정하는 상황 재현)
        LocalDate tradeDate = LocalDate.of(2026, 3, 10);
        MydataFundResponseDTO fund =
                MydataFundResponseDTO.builder()
                        .fundCode("448630")
                        .foreignStockRatio(BigDecimal.valueOf(80.00))
                        .inceptionDate(LocalDate.of(2026, 3, 5))
                        .build();
        when(mydataFundClient.getFund("448630")).thenReturn(fund);
        MydataTradeResponseDTO t =
                trade(12L, "BUY", "FUND", "448630", BigDecimal.valueOf(1_000_000), tradeDate);

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getIsTarget()).isFalse();
    }

    @Test
    @DisplayName("판정 결과를 mapper에 저장하고, 거래 원본 정보(트레이드타입/금액/거래일)도 함께 저장한다")
    void judgePassesResultToMapper() {
        ArgumentCaptor<TargetProductJudgementDTO> captor =
                ArgumentCaptor.forClass(TargetProductJudgementDTO.class);
        MydataTradeResponseDTO t =
                trade(
                        7L,
                        "BUY",
                        "ETN",
                        null,
                        BigDecimal.valueOf(200_000),
                        LocalDate.of(2026, 3, 15));

        targetProductService.judge(t);

        verify(targetProductMapper).insertJudgement(captor.capture());
        assertThat(captor.getValue().getMydataTradeId()).isEqualTo(7L);
        assertThat(captor.getValue().getCiHash()).isEqualTo("ci-1");
        assertThat(captor.getValue().getStockType()).isEqualTo(StockType.ETN);
        assertThat(captor.getValue().getTicker()).isEqualTo("AAPL");
        assertThat(captor.getValue().getJudgedAt()).isEqualTo(FIXED_NOW);
        assertThat(captor.getValue().getTradeType()).isEqualTo(TradeType.BUY);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("200000");
        assertThat(captor.getValue().getTradeDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    @DisplayName("BUY 거래는 금액 그대로 양수의 net_buy_amount로 저장한다")
    void judgeSetsPositiveNetBuyAmountForBuyTrade() {
        MydataTradeResponseDTO t =
                trade(
                        8L,
                        "BUY",
                        "FOREIGN_STOCK",
                        null,
                        BigDecimal.valueOf(1_000_000),
                        LocalDate.of(2026, 3, 5));

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getNetBuyAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("SELL 거래는 금액이 음수로 부호전환된 net_buy_amount로 저장한다")
    void judgeSetsNegativeNetBuyAmountForSellTrade() {
        MydataTradeResponseDTO t =
                trade(
                        9L,
                        "SELL",
                        "FOREIGN_STOCK",
                        null,
                        BigDecimal.valueOf(1_000_000),
                        LocalDate.of(2026, 3, 5));

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getNetBuyAmount()).isEqualByComparingTo("-1000000");
    }

    @Test
    @DisplayName("INHERITANCE(상속) 거래는 BUY와 동일하게 양수의 net_buy_amount로 저장한다")
    void judgeSetsPositiveNetBuyAmountForInheritanceTrade() {
        MydataTradeResponseDTO t =
                trade(
                        10L,
                        "INHERITANCE",
                        "FOREIGN_STOCK",
                        null,
                        BigDecimal.valueOf(1_000_000),
                        LocalDate.of(2026, 3, 5));

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getNetBuyAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("GIFT(증여) 거래는 BUY와 동일하게 양수의 net_buy_amount로 저장한다")
    void judgeSetsPositiveNetBuyAmountForGiftTrade() {
        MydataTradeResponseDTO t =
                trade(
                        11L,
                        "GIFT",
                        "FOREIGN_STOCK",
                        null,
                        BigDecimal.valueOf(1_000_000),
                        LocalDate.of(2026, 3, 5));

        TargetProductJudgementDTO result = targetProductService.judge(t);

        assertThat(result.getNetBuyAmount()).isEqualByComparingTo("1000000");
    }
}
