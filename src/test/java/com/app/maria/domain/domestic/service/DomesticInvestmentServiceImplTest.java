package com.app.maria.domain.domestic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.domestic.dto.DomesticAccountDetailDTO;
import com.app.maria.domain.domestic.dto.DomesticAccountLiteDTO;
import com.app.maria.domain.domestic.dto.DomesticCashHeavyAccountDTO;
import com.app.maria.domain.domestic.dto.DomesticFundHoldingDetailDTO;
import com.app.maria.domain.domestic.dto.DomesticHoldingDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentListDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentPageDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentSearchDTO;
import com.app.maria.domain.domestic.dto.DomesticInvestmentSummaryDTO;
import com.app.maria.domain.domestic.dto.DomesticRestrictedHoldingDTO;
import com.app.maria.domain.domestic.dto.DomesticTradeHistoryDTO;
import com.app.maria.domain.domestic.dto.request.DomesticInvestmentSearchRequestDTO;
import com.app.maria.domain.domestic.dto.request.DomesticTradeRequestDTO;
import com.app.maria.domain.domestic.mapper.DomesticStockBalanceMapper;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.ApiResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class DomesticInvestmentServiceImplTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long CUSTOMER_ID = 5177L;
    private static final String CI_HASH = "ci-hash-5177";

    @Mock private DomesticStockBalanceMapper domesticStockBalanceMapper;

    @Mock private AccountMapper accountMapper;

    @Mock private DomesticPurchaseEligibilityService domesticPurchaseEligibilityService;

    @Mock private RestClient restClient;

    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock private RestClient.RequestBodySpec requestBodySpec;

    @Mock private RestClient.ResponseSpec responseSpec;

    @Mock private BusinessClockService businessClockService;

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 19, 9, 0);

    private DomesticInvestmentServiceImpl domesticInvestmentService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        domesticInvestmentService =
                new DomesticInvestmentServiceImpl(
                        domesticStockBalanceMapper,
                        accountMapper,
                        domesticPurchaseEligibilityService,
                        restClient,
                        businessClockService);

        lenient().when(businessClockService.now()).thenReturn(FIXED_NOW);
        lenient()
                .when(accountMapper.selectByAccountId(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(
                                AccountDTO.builder()
                                        .accountId(ACCOUNT_ID)
                                        .customerId(CUSTOMER_ID)
                                        .build()));
        lenient()
                .when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(CI_HASH));
        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient()
                .when(requestBodyUriSpec.uri(eq("/api/domestic-trades")))
                .thenReturn(requestBodySpec);
        lenient()
                .when(requestBodySpec.body(any(DomesticTradeRequestDTO.class)))
                .thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("getInvestments()는 totalElements를 size로 나눈 뒤 올림해 totalPages를 계산한다")
    void getInvestmentsCalculatesTotalPagesFromTotalElements() {
        when(domesticStockBalanceMapper.selectAccountSummaries(any())).thenReturn(List.of());
        when(domesticStockBalanceMapper.countAccountSummaries(any())).thenReturn(45);

        DomesticInvestmentSearchRequestDTO request =
                DomesticInvestmentSearchRequestDTO.builder().page(0).size(20).build();

        DomesticInvestmentPageDTO result = domesticInvestmentService.getInvestments(request);

        assertThat(result.getTotalElements()).isEqualTo(45);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("getInvestments()는 검색 조건(keyword/페이지/사이즈)을 mapper에 그대로 전달한다")
    void getInvestmentsPassesSearchConditionToMapper() {
        when(domesticStockBalanceMapper.selectAccountSummaries(any())).thenReturn(List.of());
        when(domesticStockBalanceMapper.countAccountSummaries(any())).thenReturn(0);

        DomesticInvestmentSearchRequestDTO request =
                DomesticInvestmentSearchRequestDTO.builder()
                        .keyword("홍길동")
                        .page(2)
                        .size(10)
                        .build();

        domesticInvestmentService.getInvestments(request);

        var captor = org.mockito.ArgumentCaptor.forClass(DomesticInvestmentSearchDTO.class);
        verify(domesticStockBalanceMapper).selectAccountSummaries(captor.capture());
        assertThat(captor.getValue().getKeyword()).isEqualTo("홍길동");
        assertThat(captor.getValue().getOffset()).isEqualTo(20);
        assertThat(captor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("getInvestments()는 hasUnpurchasableHolding=true면 매수불가 계좌ID로 필터링한다")
    void getInvestmentsFiltersByResolvedUnpurchasableAccountIds() {
        DomesticFundHoldingDetailDTO ineligible =
                DomesticFundHoldingDetailDTO.builder()
                        .accountId(9L)
                        .domesticStockRatio(BigDecimal.valueOf(40))
                        .inceptionDate(LocalDate.of(2020, 1, 1))
                        .build();
        when(domesticStockBalanceMapper.selectActiveFundHoldings()).thenReturn(List.of(ineligible));
        when(domesticPurchaseEligibilityService.isPurchasable(
                        Type.FUND, BigDecimal.valueOf(40), LocalDate.of(2020, 1, 1)))
                .thenReturn(false);
        when(domesticStockBalanceMapper.selectAccountSummaries(any())).thenReturn(List.of());
        when(domesticStockBalanceMapper.countAccountSummaries(any())).thenReturn(0);

        DomesticInvestmentSearchRequestDTO request =
                DomesticInvestmentSearchRequestDTO.builder()
                        .hasUnpurchasableHolding(true)
                        .page(0)
                        .size(20)
                        .build();

        domesticInvestmentService.getInvestments(request);

        var captor = org.mockito.ArgumentCaptor.forClass(DomesticInvestmentSearchDTO.class);
        verify(domesticStockBalanceMapper).selectAccountSummaries(captor.capture());
        assertThat(captor.getValue().getUnpurchasableAccountIds()).containsExactly(9L);
    }

    @Test
    @DisplayName("getInvestments()는 매수불가 계좌가 하나도 없으면 mapper 조회 없이 빈 페이지를 반환한다")
    void getInvestmentsReturnsEmptyPageWhenNoUnpurchasableAccountsExist() {
        when(domesticStockBalanceMapper.selectActiveFundHoldings()).thenReturn(List.of());

        DomesticInvestmentSearchRequestDTO request =
                DomesticInvestmentSearchRequestDTO.builder()
                        .hasUnpurchasableHolding(true)
                        .page(0)
                        .size(20)
                        .build();

        var result = domesticInvestmentService.getInvestments(request);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
        verify(domesticStockBalanceMapper, org.mockito.Mockito.never())
                .selectAccountSummaries(any());
    }

    @Test
    @DisplayName(
            "getInvestments()는 hasRecentBuy가 주어지면 BusinessClockService 기준으로 recentBuySinceDate를 계산해 전달한다")
    void getInvestmentsResolvesRecentBuySinceDateFromClock() {
        when(domesticStockBalanceMapper.selectAccountSummaries(any())).thenReturn(List.of());
        when(domesticStockBalanceMapper.countAccountSummaries(any())).thenReturn(0);

        DomesticInvestmentSearchRequestDTO request =
                DomesticInvestmentSearchRequestDTO.builder()
                        .hasRecentBuy(true)
                        .recentBuyDays(14)
                        .page(0)
                        .size(20)
                        .build();

        domesticInvestmentService.getInvestments(request);

        var captor = org.mockito.ArgumentCaptor.forClass(DomesticInvestmentSearchDTO.class);
        verify(domesticStockBalanceMapper).selectAccountSummaries(captor.capture());
        assertThat(captor.getValue().getHasRecentBuy()).isTrue();
        assertThat(captor.getValue().getRecentBuySinceDate())
                .isEqualTo(FIXED_NOW.toLocalDate().minusDays(14));
    }

    @Test
    @DisplayName("getAccountDetail()은 예탁금·보유종목·매매내역을 조합해서 반환한다")
    @SuppressWarnings("unchecked")
    void getAccountDetailReturnsSummaryHoldingsAndTradeHistory() {
        when(domesticStockBalanceMapper.selectAccountSummaryById(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(
                                DomesticInvestmentListDTO.builder()
                                        .accountId(ACCOUNT_ID)
                                        .accountNo("1234567890")
                                        .customerName("홍길동")
                                        .cashAmount(BigDecimal.valueOf(500000))
                                        .build()));
        DomesticHoldingDTO holding =
                DomesticHoldingDTO.builder().domesticProductId(1L).ticker("005930").build();
        when(domesticStockBalanceMapper.selectHoldingsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of(holding));
        when(domesticPurchaseEligibilityService.isPurchasable(any(), any(), any()))
                .thenReturn(true);

        DomesticTradeHistoryDTO trade =
                DomesticTradeHistoryDTO.builder().stockCode("005930").tradeType("BUY").build();
        ApiResponseDTO<List<DomesticTradeHistoryDTO>> apiResponse =
                ApiResponseDTO.of("조회 성공", List.of(trade));
        when(responseSpec.body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .thenReturn(apiResponse);

        DomesticAccountDetailDTO result = domesticInvestmentService.getAccountDetail(ACCOUNT_ID);

        assertThat(result.getAccountNo()).isEqualTo("1234567890");
        assertThat(result.getCustomerName()).isEqualTo("홍길동");
        assertThat(result.getHoldings()).hasSize(1);
        assertThat(result.getHoldings().get(0).getCurrentlyPurchasable()).isTrue();
        assertThat(result.getTradeHistory()).hasSize(1);
        assertThat(result.getTradeHistory().get(0).getStockCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("계좌 요약이 없으면 AppException(DOMESTIC_INVESTMENT_NOT_FOUND)을 던진다")
    void getAccountDetailThrowsWhenAccountSummaryNotFound() {
        when(domesticStockBalanceMapper.selectAccountSummaryById(ACCOUNT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> domesticInvestmentService.getAccountDetail(ACCOUNT_ID))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.DOMESTIC_INVESTMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("고객의 ciHash를 찾을 수 없으면 ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND를 던진다")
    void getAccountDetailThrowsWhenCiHashMissing() {
        when(domesticStockBalanceMapper.selectAccountSummaryById(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(
                                DomesticInvestmentListDTO.builder().accountId(ACCOUNT_ID).build()));
        when(domesticStockBalanceMapper.selectHoldingsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of());
        when(accountMapper.selectCiHashByCustomerId(CUSTOMER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> domesticInvestmentService.getAccountDetail(ACCOUNT_ID))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("증권사 응답 data가 null이면 매매내역을 빈 리스트로 반환한다")
    @SuppressWarnings("unchecked")
    void getAccountDetailReturnsEmptyTradeHistoryWhenApiResponseDataIsNull() {
        when(domesticStockBalanceMapper.selectAccountSummaryById(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(
                                DomesticInvestmentListDTO.builder().accountId(ACCOUNT_ID).build()));
        when(domesticStockBalanceMapper.selectHoldingsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of());
        ApiResponseDTO<List<DomesticTradeHistoryDTO>> apiResponse =
                ApiResponseDTO.of("조회 실패", null);
        when(responseSpec.body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .thenReturn(apiResponse);

        DomesticAccountDetailDTO result = domesticInvestmentService.getAccountDetail(ACCOUNT_ID);

        assertThat(result.getTradeHistory()).isEmpty();
    }

    @Test
    @DisplayName("증권사 시스템 연결 실패 시 매매내역을 빈 리스트로 반환한다")
    @SuppressWarnings("unchecked")
    void getAccountDetailReturnsEmptyTradeHistoryWhenReturnSecuritiesCallFails() {
        when(domesticStockBalanceMapper.selectAccountSummaryById(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(
                                DomesticInvestmentListDTO.builder().accountId(ACCOUNT_ID).build()));
        when(domesticStockBalanceMapper.selectHoldingsByAccountId(ACCOUNT_ID))
                .thenReturn(List.of());
        when(responseSpec.body(any(org.springframework.core.ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("증권사 시스템 연결 실패"));

        DomesticAccountDetailDTO result = domesticInvestmentService.getAccountDetail(ACCOUNT_ID);

        assertThat(result.getTradeHistory()).isEmpty();
    }

    @Test
    @DisplayName("getSummary(days)는 BusinessClockService의 오늘 기준 days일 전 날짜로 매퍼를 조회하고 통계를 그대로 담는다")
    void getSummaryDelegatesToMapperUsingClockAndGivenDays() {
        DomesticInvestmentSummaryDTO stats =
                DomesticInvestmentSummaryDTO.builder()
                        .totalAccountCount(20)
                        .restrictedAccountCount(3)
                        .totalCashAmount(BigDecimal.valueOf(50_000_000))
                        .domesticStockAmount(BigDecimal.valueOf(320_000_000))
                        .domesticFundAmount(BigDecimal.valueOf(150_000_000))
                        .stockHoldingAccountCount(9)
                        .fundHoldingAccountCount(5)
                        .recentBuyAccountCount(14)
                        .build();
        LocalDate expectedSinceDate = FIXED_NOW.toLocalDate().minusDays(14);
        when(domesticStockBalanceMapper.selectSummaryStats(expectedSinceDate)).thenReturn(stats);
        when(domesticStockBalanceMapper.selectActiveFundHoldings()).thenReturn(List.of());

        var result = domesticInvestmentService.getSummary(14);

        verify(domesticStockBalanceMapper).selectSummaryStats(expectedSinceDate);
        assertThat(result.getTotalAccountCount()).isEqualTo(20);
        assertThat(result.getRestrictedAccountCount()).isEqualTo(3);
        assertThat(result.getTotalCashAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(50_000_000));
        assertThat(result.getDomesticStockAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(320_000_000));
        assertThat(result.getDomesticFundAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(150_000_000));
        assertThat(result.getStockHoldingAccountCount()).isEqualTo(9);
        assertThat(result.getFundHoldingAccountCount()).isEqualTo(5);
        assertThat(result.getRecentBuyAccountCount()).isEqualTo(14);
        assertThat(result.getNoRecentBuyAccountCount()).isEqualTo(6);
        assertThat(result.getUnpurchasableHoldingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("getSummary()의 unpurchasableHoldingCount는 활성 펀드 보유 중 매수불가 요건인 것만 센다")
    void getSummaryCountsOnlyUnpurchasableActiveFundHoldings() {
        when(domesticStockBalanceMapper.selectSummaryStats(any()))
                .thenReturn(DomesticInvestmentSummaryDTO.builder().build());
        DomesticFundHoldingDetailDTO eligible =
                DomesticFundHoldingDetailDTO.builder()
                        .domesticStockRatio(BigDecimal.valueOf(85))
                        .inceptionDate(LocalDate.of(2020, 1, 1))
                        .build();
        DomesticFundHoldingDetailDTO ineligible =
                DomesticFundHoldingDetailDTO.builder()
                        .domesticStockRatio(BigDecimal.valueOf(40))
                        .inceptionDate(LocalDate.of(2020, 1, 1))
                        .build();
        when(domesticStockBalanceMapper.selectActiveFundHoldings())
                .thenReturn(List.of(eligible, ineligible));
        when(domesticPurchaseEligibilityService.isPurchasable(
                        Type.FUND, BigDecimal.valueOf(85), LocalDate.of(2020, 1, 1)))
                .thenReturn(true);
        when(domesticPurchaseEligibilityService.isPurchasable(
                        Type.FUND, BigDecimal.valueOf(40), LocalDate.of(2020, 1, 1)))
                .thenReturn(false);

        var result = domesticInvestmentService.getSummary(7);

        assertThat(result.getUnpurchasableHoldingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("getUnpurchasableHoldings()는 활성 펀드 보유 중 매수불가 요건인 것만 ResponseDTO로 반환한다")
    void getUnpurchasableHoldingsReturnsOnlyIneligibleFundHoldings() {
        DomesticFundHoldingDetailDTO eligible =
                DomesticFundHoldingDetailDTO.builder()
                        .customerName("홍길동")
                        .domesticStockRatio(BigDecimal.valueOf(85))
                        .inceptionDate(LocalDate.of(2020, 1, 1))
                        .build();
        DomesticFundHoldingDetailDTO ineligible =
                DomesticFundHoldingDetailDTO.builder()
                        .customerName("김철수")
                        .domesticStockRatio(BigDecimal.valueOf(40))
                        .inceptionDate(LocalDate.of(2020, 1, 1))
                        .build();
        when(domesticStockBalanceMapper.selectActiveFundHoldings())
                .thenReturn(List.of(eligible, ineligible));
        when(domesticPurchaseEligibilityService.isPurchasable(
                        Type.FUND, BigDecimal.valueOf(85), LocalDate.of(2020, 1, 1)))
                .thenReturn(true);
        when(domesticPurchaseEligibilityService.isPurchasable(
                        Type.FUND, BigDecimal.valueOf(40), LocalDate.of(2020, 1, 1)))
                .thenReturn(false);

        var result = domesticInvestmentService.getUnpurchasableHoldings();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("getRecentBuyAccounts()는 BusinessClockService의 오늘 기준 days일 전 날짜로 매퍼를 조회한다")
    void getRecentBuyAccountsDelegatesToMapperUsingClockAndGivenDays() {
        LocalDate expectedSinceDate = FIXED_NOW.toLocalDate().minusDays(30);
        when(domesticStockBalanceMapper.selectAccountsByRecentBuyStatus(expectedSinceDate, true))
                .thenReturn(List.of());

        var result = domesticInvestmentService.getRecentBuyAccounts(30, true);

        verify(domesticStockBalanceMapper).selectAccountsByRecentBuyStatus(expectedSinceDate, true);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getRecentBuyAccounts()는 매퍼 결과를 ResponseDTO로 그대로 변환한다")
    void getRecentBuyAccountsMapsMapperResultToResponseDTOs() {
        DomesticAccountLiteDTO account =
                DomesticAccountLiteDTO.builder()
                        .accountId(ACCOUNT_ID)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .build();
        when(domesticStockBalanceMapper.selectAccountsByRecentBuyStatus(any(), eq(false)))
                .thenReturn(List.of(account));

        var result = domesticInvestmentService.getRecentBuyAccounts(7, false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("getRestrictedHoldings()는 매퍼 결과를 ResponseDTO로 그대로 변환한다")
    void getRestrictedHoldingsMapsMapperResultToResponseDTOs() {
        DomesticRestrictedHoldingDTO holding =
                DomesticRestrictedHoldingDTO.builder()
                        .customerName("홍길동")
                        .accountNo("1234567890")
                        .productName("삼성바이오로직스")
                        .ticker("207940")
                        .status(DomesticStockStatus.TRADE_SUSPENDED)
                        .build();
        when(domesticStockBalanceMapper.selectRestrictedHoldings()).thenReturn(List.of(holding));

        var result = domesticInvestmentService.getRestrictedHoldings();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
        assertThat(result.get(0).getStatus()).isEqualTo(DomesticStockStatus.TRADE_SUSPENDED);
    }

    @Test
    @DisplayName("getRestrictedHoldings()는 대상이 없으면 빈 목록을 반환한다")
    void getRestrictedHoldingsReturnsEmptyListWhenNoneExist() {
        when(domesticStockBalanceMapper.selectRestrictedHoldings()).thenReturn(List.of());

        var result = domesticInvestmentService.getRestrictedHoldings();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getCashHeavyAccounts()는 매퍼 결과를 페이지 ResponseDTO로 변환하며 cashRatio를 계산한다")
    void getCashHeavyAccountsMapsMapperResultAndComputesCashRatio() {
        DomesticCashHeavyAccountDTO account =
                DomesticCashHeavyAccountDTO.builder()
                        .accountId(ACCOUNT_ID)
                        .accountNo("1234567890")
                        .customerName("홍길동")
                        .cashAmount(BigDecimal.valueOf(600_000))
                        .investedAmount(BigDecimal.valueOf(400_000))
                        .build();
        when(domesticStockBalanceMapper.selectCashHeavyAccounts(0, 20))
                .thenReturn(List.of(account));
        when(domesticStockBalanceMapper.countCashHeavyAccounts()).thenReturn(1);

        var result = domesticInvestmentService.getCashHeavyAccounts(0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCashRatio())
                .isEqualByComparingTo(BigDecimal.valueOf(0.6000));
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("getCashHeavyAccounts()는 totalElements를 size로 나눈 뒤 올림해 totalPages를 계산한다")
    void getCashHeavyAccountsCalculatesTotalPagesFromTotalElements() {
        when(domesticStockBalanceMapper.selectCashHeavyAccounts(0, 20)).thenReturn(List.of());
        when(domesticStockBalanceMapper.countCashHeavyAccounts()).thenReturn(45);

        var result = domesticInvestmentService.getCashHeavyAccounts(0, 20);

        assertThat(result.getTotalElements()).isEqualTo(45);
        assertThat(result.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("getCashHeavyAccounts()는 대상이 없으면 빈 목록을 반환한다")
    void getCashHeavyAccountsReturnsEmptyListWhenNoneExist() {
        when(domesticStockBalanceMapper.selectCashHeavyAccounts(0, 20)).thenReturn(List.of());
        when(domesticStockBalanceMapper.countCashHeavyAccounts()).thenReturn(0);

        var result = domesticInvestmentService.getCashHeavyAccounts(0, 20);

        assertThat(result.getContent()).isEmpty();
    }
}
