package com.app.maria.domain.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.asset.dto.AssetHoldingRowDTO;
import com.app.maria.domain.asset.dto.response.CustomerAssetHoldingResponseDTO;
import com.app.maria.domain.asset.mapper.AssetBriefingMapper;
import com.app.maria.domain.asset.price.AssetPriceProvider;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerAssetHoldingsServiceImplTest {

    private static final LocalDateTime PRICE_AT = LocalDateTime.of(2026, 11, 3, 15, 30);

    @Mock AccountMapper accountMapper;
    @Mock AssetBriefingMapper assetBriefingMapper;
    @Mock AssetPriceProvider assetPriceProvider;
    @Mock RiskGradeService riskGradeService;
    @InjectMocks CustomerAssetHoldingsServiceImpl service;

    private void stubOpenedAccount() {
        AccountDTO account = AccountDTO.builder().accountId(100L).status(Status.OPENED).build();
        when(accountMapper.selectByCustomerId(7L)).thenReturn(Optional.of(account));
    }

    private static AssetHoldingRowDTO row(
            String ticker, String qty, String avg, DomesticStockStatus status) {
        AssetHoldingRowDTO r = new AssetHoldingRowDTO();
        r.setTicker(ticker);
        r.setName(ticker + "이름");
        r.setType(Type.STOCK);
        r.setStatus(status);
        r.setQty(new BigDecimal(qty));
        r.setAvgPurchasePrice(new BigDecimal(avg));
        return r;
    }

    private static UsableRiskGradeDTO grade(String ticker, int riskGrade) {
        return new UsableRiskGradeDTO(
                1L,
                ticker,
                ticker + "이름",
                new BigDecimal("0.0300"),
                riskGrade,
                new BigDecimal("0.2000"),
                LocalDate.of(2023, 11, 3),
                LocalDate.of(2026, 11, 3),
                1L,
                RiskDataStatus.CURRENT);
    }

    private void stubRows(AssetHoldingRowDTO... rows) {
        when(assetBriefingMapper.selectHoldingRows(
                        100L, AssetBriefingMapper.ACTIVE_HOLDING_STATUSES))
                .thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("시세가 있으면 평가금액·평가손익·수익률을 계산하고 위험등급을 붙인다")
    void pricedRowGetsValuationAndRiskGrade() {
        stubOpenedAccount();
        stubRows(row("005930", "10", "70000", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet()))
                .thenReturn(
                        Map.of(
                                "005930",
                                new AssetPriceProvider.Quote(new BigDecimal("77000"), PRICE_AT)));
        when(riskGradeService.findUsableByTickers(anySet()))
                .thenReturn(Map.of("005930", grade("005930", 3)));

        CustomerAssetHoldingResponseDTO h = service.getHoldings(7L).get(0);

        assertThat(h.getPurchaseAmount()).isEqualByComparingTo("700000");
        assertThat(h.getCurrentPrice()).isEqualByComparingTo("77000");
        assertThat(h.getPriceAt()).isEqualTo(PRICE_AT);
        assertThat(h.getEvaluationAmount()).isEqualByComparingTo("770000");
        assertThat(h.getProfit()).isEqualByComparingTo("70000");
        assertThat(h.getProfitRate()).isEqualByComparingTo("10.00");
        assertThat(h.getRiskGrade()).isEqualTo(3);
        assertThat(h.getRiskGradeStatus()).isEqualTo(RiskDataStatus.CURRENT);
        assertThat(h.getUnavailableFields()).isEmpty();
    }

    @Test
    @DisplayName("시세를 못 받은 종목은 시세 칸만 null이고 사유 목록에 이름이 들어가며 표는 나간다")
    void unpricedRowHasNullValuationFields() {
        stubOpenedAccount();
        stubRows(
                row("005930", "10", "70000", DomesticStockStatus.HOLDING),
                row("000660", "2", "100000", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet()))
                .thenReturn(
                        Map.of(
                                "005930",
                                new AssetPriceProvider.Quote(new BigDecimal("77000"), PRICE_AT)));
        when(riskGradeService.findUsableByTickers(anySet())).thenReturn(Map.of());

        List<CustomerAssetHoldingResponseDTO> result = service.getHoldings(7L);

        CustomerAssetHoldingResponseDTO noPrice = result.get(1);
        assertThat(noPrice.getCurrentPrice()).isNull();
        assertThat(noPrice.getEvaluationAmount()).isNull();
        assertThat(noPrice.getProfit()).isNull();
        assertThat(noPrice.getProfitRate()).isNull();
        assertThat(noPrice.getPurchaseAmount()).isEqualByComparingTo("200000");
        assertThat(noPrice.getUnavailableFields())
                .containsExactly(
                        "currentPrice", "evaluationAmount", "profit", "profitRate", "riskGrade");
        assertThat(result.get(0).getUnavailableFields()).containsExactly("riskGrade");
    }

    @Test
    @DisplayName("기본 시세 제공자(항상 빈 값)면 모든 행의 시세가 null이다")
    void emptyQuotesMeansAllUnavailable() {
        stubOpenedAccount();
        stubRows(row("005930", "10", "70000", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet())).thenReturn(Map.of());
        when(riskGradeService.findUsableByTickers(anySet())).thenReturn(Map.of());

        CustomerAssetHoldingResponseDTO h = service.getHoldings(7L).get(0);

        assertThat(h.getCurrentPrice()).isNull();
        assertThat(h.getEvaluationAmount()).isNull();
    }

    @Test
    @DisplayName("시세 조회 불가·위험등급 달력 오류가 나도 표는 나가고 해당 칸만 null이다")
    void externalFailuresDegradeInsteadOfFailing() {
        stubOpenedAccount();
        stubRows(row("005930", "10", "70000", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet()))
                .thenThrow(new AppException(ErrorType.ASSET_PRICE_UNAVAILABLE));
        when(riskGradeService.findUsableByTickers(anySet()))
                .thenThrow(new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE));

        CustomerAssetHoldingResponseDTO h = service.getHoldings(7L).get(0);

        assertThat(h.getCurrentPrice()).isNull();
        assertThat(h.getRiskGrade()).isNull();
        assertThat(h.getUnavailableFields()).contains("currentPrice", "riskGrade");
    }

    @Test
    @DisplayName("시세 제공자가 어떤 런타임 예외를 던져도 표는 나가고 시세 칸만 null이다")
    void anyProviderFailureDegrades() {
        stubOpenedAccount();
        stubRows(row("005930", "10", "70000", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet()))
                .thenThrow(new IllegalStateException("json parse"));
        when(riskGradeService.findUsableByTickers(anySet())).thenReturn(Map.of());

        CustomerAssetHoldingResponseDTO h = service.getHoldings(7L).get(0);

        assertThat(h.getCurrentPrice()).isNull();
        assertThat(h.getUnavailableFields()).contains("currentPrice");
    }

    @Test
    @DisplayName("위험등급 쪽(RISK_*)이 아닌 AppException은 가리지 않고 전파한다")
    void nonRiskAppExceptionPropagates() {
        stubOpenedAccount();
        stubRows(row("005930", "10", "70000", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet())).thenReturn(Map.of());
        when(riskGradeService.findUsableByTickers(anySet()))
                .thenThrow(new AppException(ErrorType.ACCOUNT_NOT_FOUND));

        assertThatThrownBy(() -> service.getHoldings(7L)).isInstanceOf(AppException.class);
    }

    @Test
    @DisplayName("손익은 반올림된 평가금액에서 반올림된 매입금액을 뺀 값이라 화면의 두 금액 차이와 같다")
    void profitEqualsDifferenceOfRoundedAmounts() {
        stubOpenedAccount();
        // 매입 10.004 → 10.00, 평가 10.016 → 10.02. 반올림 전 차이 0.012는 0.01이지만 화면의 두 금액 차이는 0.02다.
        stubRows(row("005930", "1", "10.004", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet()))
                .thenReturn(
                        Map.of(
                                "005930",
                                new AssetPriceProvider.Quote(new BigDecimal("10.016"), PRICE_AT)));
        when(riskGradeService.findUsableByTickers(anySet())).thenReturn(Map.of());

        CustomerAssetHoldingResponseDTO h = service.getHoldings(7L).get(0);

        assertThat(h.getProfit()).isEqualByComparingTo("0.02");
        assertThat(h.getProfit())
                .isEqualByComparingTo(h.getEvaluationAmount().subtract(h.getPurchaseAmount()));
    }

    @Test
    @DisplayName("매입금액이 0이면 수익률을 정할 수 없어 profitRate가 null이고 사유에 들어간다")
    void zeroCostHasNullProfitRateWithReason() {
        stubOpenedAccount();
        stubRows(row("005930", "5", "0", DomesticStockStatus.HOLDING));
        when(assetPriceProvider.getPrices(anySet()))
                .thenReturn(
                        Map.of(
                                "005930",
                                new AssetPriceProvider.Quote(new BigDecimal("1000"), PRICE_AT)));
        when(riskGradeService.findUsableByTickers(anySet())).thenReturn(Map.of());

        CustomerAssetHoldingResponseDTO h = service.getHoldings(7L).get(0);

        assertThat(h.getProfit()).isEqualByComparingTo("5000");
        assertThat(h.getProfitRate()).isNull();
        assertThat(h.getUnavailableFields()).contains("profitRate");
    }

    @Test
    @DisplayName("매도 가능 수량: 보유·일부매도는 보유 수량, 거래제한·거래정지는 0이다")
    void sellableQtyDependsOnStatus() {
        stubOpenedAccount();
        stubRows(
                row("A", "10", "1", DomesticStockStatus.HOLDING),
                row("B", "4", "1", DomesticStockStatus.PARTIALLY_SOLD),
                row("C", "3", "1", DomesticStockStatus.TRADE_RESTRICTED),
                row("D", "2", "1", DomesticStockStatus.TRADE_SUSPENDED));
        when(assetPriceProvider.getPrices(anySet())).thenReturn(Map.of());
        when(riskGradeService.findUsableByTickers(anySet())).thenReturn(Map.of());

        List<CustomerAssetHoldingResponseDTO> result = service.getHoldings(7L);

        assertThat(result)
                .extracting(h -> h.getSellableQty().intValue())
                .containsExactly(10, 4, 0, 0);
        assertThat(result.get(2).getQty()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("보유 종목이 없으면 빈 목록이고 시세·위험등급을 부르지 않는다")
    void noHoldingsSkipsExternalCalls() {
        stubOpenedAccount();
        stubRows();

        assertThat(service.getHoldings(7L)).isEmpty();
        verifyNoInteractions(assetPriceProvider, riskGradeService);
    }

    @Test
    @DisplayName("OPENED가 아닌 계좌나 계좌 없음은 ACCOUNT_NOT_FOUND다")
    void rejectsNonOpenedOrMissingAccount() {
        when(accountMapper.selectByCustomerId(7L))
                .thenReturn(
                        Optional.of(
                                AccountDTO.builder()
                                        .accountId(100L)
                                        .status(Status.CLOSED)
                                        .build()));

        assertThatThrownBy(() -> service.getHoldings(7L))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.ACCOUNT_NOT_FOUND.getMessage());
        verifyNoInteractions(assetBriefingMapper, assetPriceProvider);
    }
}
