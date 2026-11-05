package com.app.maria.domain.asset.service;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.asset.dto.AssetHoldingRowDTO;
import com.app.maria.domain.asset.dto.response.CustomerAssetHoldingResponseDTO;
import com.app.maria.domain.asset.mapper.AssetBriefingMapper;
import com.app.maria.domain.asset.price.AssetPriceProvider;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.risk.dto.UsableRiskGradeDTO;
import com.app.maria.domain.risk.service.RiskGradeService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 트랜잭션을 두지 않는다: 시세·위험등급 조회(외부 호출 가능) 동안 DB 커넥션을 쥐지 않기 위해서다. 시세나 위험등급을 못 받아도 표는 나가고 그 칸만 null이다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerAssetHoldingsServiceImpl implements CustomerAssetHoldingsService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AccountMapper accountMapper;
    private final AssetBriefingMapper assetBriefingMapper;
    private final AssetPriceProvider assetPriceProvider;
    private final RiskGradeService riskGradeService;

    @Override
    public List<CustomerAssetHoldingResponseDTO> getHoldings(Long customerId) {
        AccountDTO account =
                accountMapper
                        .selectByCustomerId(customerId)
                        .filter(value -> value.getStatus() == Status.OPENED)
                        .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND));
        List<AssetHoldingRowDTO> rows =
                assetBriefingMapper.selectHoldingRows(
                        account.getAccountId(), AssetBriefingMapper.ACTIVE_HOLDING_STATUSES);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> tickers =
                rows.stream().map(AssetHoldingRowDTO::getTicker).collect(Collectors.toSet());
        Map<String, AssetPriceProvider.Quote> quotes = quotesOf(tickers);
        Map<String, UsableRiskGradeDTO> grades = riskGradesOf(tickers);
        return rows.stream().map(row -> toResponse(row, quotes, grades)).toList();
    }

    private CustomerAssetHoldingResponseDTO toResponse(
            AssetHoldingRowDTO row,
            Map<String, AssetPriceProvider.Quote> quotes,
            Map<String, UsableRiskGradeDTO> grades) {
        BigDecimal purchaseAmount = row.getQty().multiply(row.getAvgPurchasePrice());
        AssetPriceProvider.Quote quote = quotes.get(row.getTicker());
        boolean priced =
                quote != null
                        && quote.priceKrw() != null
                        && quote.priceKrw().signum() > 0
                        && quote.priceAt() != null;
        UsableRiskGradeDTO grade = grades.get(row.getTicker());

        List<String> unavailable = new ArrayList<>();
        BigDecimal evaluation = null;
        BigDecimal profit = null;
        BigDecimal profitRate = null;
        if (priced) {
            evaluation = row.getQty().multiply(quote.priceKrw());
            // 화면에 나란히 보이는 반올림된 평가금액·매입금액의 차이가 손익과 같도록 반올림 후 뺀다.
            profit = money(evaluation).subtract(money(purchaseAmount));
            if (purchaseAmount.signum() > 0) {
                profitRate =
                        evaluation
                                .subtract(purchaseAmount)
                                .multiply(HUNDRED)
                                .divide(purchaseAmount, 2, RoundingMode.HALF_UP);
            } else {
                unavailable.add("profitRate"); // 매입금액 0(무상 지급 등)이면 수익률을 정할 수 없다.
            }
        } else {
            unavailable.addAll(List.of("currentPrice", "evaluationAmount", "profit", "profitRate"));
        }
        if (grade == null) {
            unavailable.add("riskGrade");
        }
        return new CustomerAssetHoldingResponseDTO(
                row.getTicker(),
                row.getName(),
                row.getSectorName(),
                row.getType(),
                row.getStatus(),
                row.getQty(),
                sellableQty(row),
                row.getAvgPurchasePrice(),
                money(purchaseAmount),
                priced ? quote.priceKrw() : null,
                priced ? quote.priceAt() : null,
                money(evaluation),
                money(profit),
                profitRate,
                grade == null ? null : grade.riskGrade(),
                grade == null ? null : grade.status(),
                List.copyOf(unavailable));
    }

    /** 거래제한·거래정지 종목은 팔 수 없다(주문 쪽 거래 제한 규칙과 같게 맞춰야 한다). */
    private static BigDecimal sellableQty(AssetHoldingRowDTO row) {
        boolean sellable =
                row.getStatus() == DomesticStockStatus.HOLDING
                        || row.getStatus() == DomesticStockStatus.PARTIALLY_SOLD;
        return sellable ? row.getQty() : BigDecimal.ZERO;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, AssetPriceProvider.Quote> quotesOf(Set<String> tickers) {
        try {
            Map<String, AssetPriceProvider.Quote> quotes = assetPriceProvider.getPrices(tickers);
            return quotes == null ? Map.of() : quotes;
        } catch (RuntimeException e) {
            // 외부 시세 제공자의 어떤 실패든 그 칸만 null로 낮춘다. 토큰·URL이 들어 있을 수 있어 종류만 남기고 메시지는 남기지 않는다.
            log.warn("보유 종목 현재가 조회 실패({}): 시세 없이 반환합니다.", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, UsableRiskGradeDTO> riskGradesOf(Set<String> tickers) {
        try {
            return riskGradeService.findUsableByTickers(tickers);
        } catch (AppException e) {
            // 위험등급 쪽(RISK_*) 오류만 그 칸 null로 낮춘다. 다른 오류는 가리지 않는다.
            if (!e.getErrorType().name().startsWith("RISK_")) throw e;
            log.warn("보유 종목 위험등급 조회 불가({}): 위험등급 없이 반환합니다.", e.getErrorType());
            return Map.of();
        }
    }
}
