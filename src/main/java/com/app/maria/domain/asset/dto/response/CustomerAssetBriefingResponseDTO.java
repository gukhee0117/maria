package com.app.maria.domain.asset.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** 저장 데이터와 조회된 가격으로 계산하는 브리핑. 현재가가 없는 값을 매입금액으로 대체하지 않는다. */
@Getter
@Builder
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

    /** 계산에 사용한 가장 이른/늦은 가격 기준시각. 무보유·가격 조회 불가이면 null. */
    private LocalDateTime valuationPriceFrom;

    private LocalDateTime valuationPriceTo;

    /** 패키지·현재가 연동 전에는 null. */
    private PackageDeviation packageDeviation;

    /** 업무시각까지 체결된 BUY 주문의 예수금 변동액 절댓값 합. 매수 없는 월은 생략한다. */
    private List<MonthlyPurchase> monthlyPurchases;

    /** 계산 불가 필드를 명시한다. null은 0 또는 비중 차이 없음이라는 뜻이 아니다. */
    private List<String> unavailableFields;

    /** 조회·집계 업무시각. 가격 기준시각이나 과거 보유잔고 스냅샷 시각은 아니다. */
    private LocalDateTime calculatedAt;

    public record MonthlyPurchase(String month, BigDecimal buyAmount) {}

    public record PackageDeviation(
            Long stockPackageId,
            Long domesticProductId,
            BigDecimal targetWeight,
            BigDecimal currentWeight,
            BigDecimal differencePercentagePoints) {}
}
