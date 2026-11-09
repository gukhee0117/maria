package com.app.maria.domain.asset.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 서비스에서 계산한 자산 브리핑 결과. API 응답 변환은 ResponseDTO 생성자가 담당한다. */
@Getter
@Builder
public class AssetBriefingVO {
    private final Long accountId;
    private final BigDecimal cashAmount;
    private final BigDecimal investmentCostAmount;
    private final BigDecimal cumulativeInflowAmount;
    private final BigDecimal investmentRatio;
    private final BigDecimal domesticValuationAmount;
    private final BigDecimal valuationProfit;
    private final BigDecimal valuationReturnRate;
    private final BigDecimal foreignHeldValuationAmount;
    private final BigDecimal totalAssetAmount;
    private final BigDecimal domesticDayChangeAmount;
    private final BigDecimal domesticDayChangeRate;
    private final LocalDate dayChangeBaseDate;
    private final String dayChangePriceSource;
    private final LocalDateTime valuationPriceFrom;
    private final LocalDateTime valuationPriceTo;
    @Builder.Default private final List<MonthlyPurchase> monthlyPurchases = List.of();
    @Builder.Default private final List<String> unavailableFields = List.of();
    private final LocalDateTime calculatedAt;

    public record MonthlyPurchase(String month, BigDecimal buyAmount) {}
}
