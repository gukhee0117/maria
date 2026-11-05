package com.app.maria.domain.asset.dto.response;

import com.app.maria.domain.asset.vo.AssetBriefingVO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 저장 데이터와 조회된 가격으로 계산하는 브리핑. 현재가가 없는 값을 매입금액으로 대체하지 않는다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAssetBriefingResponseDTO {
    private Long accountId;

    /** 조회 시점 account.amount. 주문 서비스의 주문 가능 금액 검증을 대신하지 않는다. */
    private BigDecimal cashAmount;

    /** 현재 보유 국내주식·펀드의 수량 × 평균매입단가 합계. 평가금액이 아니다. */
    private BigDecimal investmentCostAmount;

    /** 업무시각까지 확정된 환전액 누계. 인출액을 차감한 잔여 원금과 다르다. */
    private BigDecimal cumulativeInflowAmount;

    /** 매입금액 / 누적 확정환전액 × 100, 소수 둘째 자리. 분모 0이면 null, 100 초과도 가능. */
    private BigDecimal investmentRatio;

    /** 모든 보유 종목의 현재가가 있을 때 수량 × 현재가 합. 예수금은 포함하지 않는다. */
    private BigDecimal domesticValuationAmount;

    /** 평가금액 - 보유자산 매입금액. 실현손익·세금·수수료는 포함하지 않는다. */
    private BigDecimal valuationProfit;

    /** 평가손익 / 보유자산 매입금액 × 100, 소수 둘째 자리. 매입금액 0이면 null. */
    private BigDecimal valuationReturnRate;

    /**
     * 입고된 해외주식의 원화 평가액(수량 × KIS 전일종가 × 환율). "해외 보유가 없음"은 0이고 "보유는 있으나 시세를 못 받음"은 null이다(시세 없는 종목을
     * 0으로 대체하면 총자산이 작게 보인다).
     */
    private BigDecimal foreignHeldValuationAmount;

    /**
     * 총자산 = 예수금 + 국내 평가금액 + 입고 해외주식 평가액. 하나라도 null이면 null이다(현재가 없는 국내 보유 종목이 있어
     * domesticValuationAmount가 null인 경우도 포함).
     */
    private BigDecimal totalAssetAmount;

    /**
     * 국내 보유 종목의 전일 대비 평가 변동. 금액은 원, 비율(domesticDayChangeRate)은 백분율(×100, 소수 둘째 자리,
     * valuationReturnRate와 같은 방식), dayChangeBaseDate는 비교한 직전 거래일이다. 금액·비율은 KIS 현재가와 기준가(전일 종가)로
     * 계산하고, 시세를 못 받은 종목이 있으면 null(0으로 대체하지 않는다). KIS 응답에 기준일이 없어 dayChangeBaseDate는 항상 null이다.
     */
    private BigDecimal domesticDayChangeAmount;

    private BigDecimal domesticDayChangeRate;
    private LocalDate dayChangeBaseDate;

    /**
     * 전일 대비 계산에 쓴 시세 출처. 전일 대비는 KIS 실시간 현재가·기준가(KIS_LIVE)로 계산하고, 평가금액은 증권사 저장
     * 가격(valuationPriceFrom/To)으로 계산해 서로 다른 기준이다. 전일 대비 금액이 null이면 null.
     */
    private String dayChangePriceSource;

    /** 계산에 사용한 가장 이른/늦은 가격 기준시각. 무보유·가격 조회 불가이면 null. */
    private LocalDateTime valuationPriceFrom;

    private LocalDateTime valuationPriceTo;

    /** 후속 패키지 연동을 위한 예약 필드. 이번 구현에서는 항상 null이다. */
    private PackageDeviation packageDeviation;

    /** 전체 기간 중 업무시각까지 체결된 BUY 주문의 예수금 변동액 절댓값 합. 매수 없는 월은 생략한다. */
    private List<MonthlyPurchase> monthlyPurchases;

    /** 계산 불가 필드를 명시한다. null은 0 또는 비중 차이 없음이라는 뜻이 아니다. */
    private List<String> unavailableFields;

    /** 조회·집계 업무시각. 가격 기준시각이나 과거 보유잔고 스냅샷 시각은 아니다. */
    private LocalDateTime calculatedAt;

    public CustomerAssetBriefingResponseDTO(AssetBriefingVO vo) {
        this.accountId = vo.getAccountId();
        this.cashAmount = vo.getCashAmount();
        this.investmentCostAmount = vo.getInvestmentCostAmount();
        this.cumulativeInflowAmount = vo.getCumulativeInflowAmount();
        this.investmentRatio = vo.getInvestmentRatio();
        this.domesticValuationAmount = vo.getDomesticValuationAmount();
        this.valuationProfit = vo.getValuationProfit();
        this.valuationReturnRate = vo.getValuationReturnRate();
        this.foreignHeldValuationAmount = vo.getForeignHeldValuationAmount();
        this.totalAssetAmount = vo.getTotalAssetAmount();
        this.domesticDayChangeAmount = vo.getDomesticDayChangeAmount();
        this.domesticDayChangeRate = vo.getDomesticDayChangeRate();
        this.dayChangeBaseDate = vo.getDayChangeBaseDate();
        this.dayChangePriceSource = vo.getDayChangePriceSource();
        this.valuationPriceFrom = vo.getValuationPriceFrom();
        this.valuationPriceTo = vo.getValuationPriceTo();
        this.monthlyPurchases =
                vo.getMonthlyPurchases().stream()
                        .map(value -> new MonthlyPurchase(value.month(), value.buyAmount()))
                        .toList();
        this.unavailableFields = List.copyOf(vo.getUnavailableFields());
        this.calculatedAt = vo.getCalculatedAt();
    }

    public record MonthlyPurchase(String month, BigDecimal buyAmount) {}

    public record PackageDeviation(
            Long stockPackageId,
            Long domesticProductId,
            BigDecimal targetWeight,
            BigDecimal currentWeight,
            BigDecimal differencePercentagePoints) {}
}
