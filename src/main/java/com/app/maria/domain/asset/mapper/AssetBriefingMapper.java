package com.app.maria.domain.asset.mapper;

import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.dto.AssetHoldingRowDTO;
import com.app.maria.domain.asset.dto.AssetMonthlyPurchaseDTO;
import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticOrderStatus;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.settlement.type.SettlementStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AssetBriefingMapper {
    List<DomesticStockStatus> ACTIVE_HOLDING_STATUSES =
            List.of(
                    DomesticStockStatus.HOLDING, DomesticStockStatus.PARTIALLY_SOLD,
                    DomesticStockStatus.TRADE_RESTRICTED, DomesticStockStatus.TRADE_SUSPENDED);

    default BigDecimal sumHoldingCost(Long accountId) {
        return sumHoldingCostByStatuses(accountId, ACTIVE_HOLDING_STATUSES);
    }

    default List<AssetHoldingDTO> selectHoldings(Long accountId) {
        return selectHoldingsByStatuses(accountId, ACTIVE_HOLDING_STATUSES);
    }

    default BigDecimal sumFinalizedInflow(Long accountId, LocalDateTime asOf) {
        return sumInflowByStatus(accountId, asOf, SettlementStatus.FINALIZED);
    }

    /** 전체 기간 집계. 기간 하한은 미확정이므로 추가하지 않고 업무시각까지의 모든 주문을 대상으로 한다. */
    default List<AssetMonthlyPurchaseDTO> selectMonthlyPurchases(
            Long accountId, LocalDateTime asOf) {
        return selectMonthlyPurchasesByStatus(
                accountId, asOf, DomesticOrderSide.BUY, DomesticOrderStatus.EXECUTED);
    }

    /** 보유 종목 표용. 매입금액(수량 × 평균매입가) 큰 순서다. */
    List<AssetHoldingRowDTO> selectHoldingRows(
            @Param("accountId") Long accountId,
            @Param("holdingStatuses") List<DomesticStockStatus> holdingStatuses);

    BigDecimal sumHoldingCostByStatuses(
            @Param("accountId") Long accountId,
            @Param("holdingStatuses") List<DomesticStockStatus> holdingStatuses);

    List<AssetHoldingDTO> selectHoldingsByStatuses(
            @Param("accountId") Long accountId,
            @Param("holdingStatuses") List<DomesticStockStatus> holdingStatuses);

    BigDecimal sumInflowByStatus(
            @Param("accountId") Long accountId,
            @Param("asOf") LocalDateTime asOf,
            @Param("settlementStatus") SettlementStatus settlementStatus);

    List<AssetMonthlyPurchaseDTO> selectMonthlyPurchasesByStatus(
            @Param("accountId") Long accountId,
            @Param("asOf") LocalDateTime asOf,
            @Param("side") DomesticOrderSide side,
            @Param("orderStatus") DomesticOrderStatus orderStatus);
}
