package com.app.maria.domain.asset.dto.response;

import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.risk.type.RiskDataStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 고객 화면의 국내 보유 종목 표 한 행. 현재가를 못 받은 종목은 currentPrice·priceAt·evaluationAmount·profit·profitRate가
 * null이고 unavailableFields에 이름이 들어간다(null은 0이 아니라 "모름"이다). riskGrade는 1=매우 높은 위험, 6=매우 낮은 위험이고 쓸 수
 * 있는 등급이 없으면 null이다. sellableQty는 거래제한·거래정지 종목이면 0이다. 매입금액이 0이면 profitRate도 null이다. 종목(ticker)당 한
 * 행을 가정한다(잔고 테이블에 계좌·종목 유일 제약은 없다).
 */
@Getter
@AllArgsConstructor
public class CustomerAssetHoldingResponseDTO {
    private final String ticker;
    private final String name;
    private final String sectorName;
    private final Type type;
    private final DomesticStockStatus status;
    private final BigDecimal qty;
    private final BigDecimal sellableQty;
    private final BigDecimal avgPurchasePrice;
    private final BigDecimal purchaseAmount;
    private final BigDecimal currentPrice;
    private final LocalDateTime priceAt;
    private final BigDecimal evaluationAmount;
    private final BigDecimal profit;
    private final BigDecimal profitRate;
    private final Integer riskGrade;
    private final RiskDataStatus riskGradeStatus;
    private final List<String> unavailableFields;
}
