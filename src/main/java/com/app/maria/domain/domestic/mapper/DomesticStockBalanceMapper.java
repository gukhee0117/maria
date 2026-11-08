package com.app.maria.domain.domestic.mapper;

import com.app.maria.domain.domestic.dto.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DomesticStockBalanceMapper {
    List<DomesticInvestmentListDTO> selectAccountSummaries(DomesticInvestmentSearchDTO condition);

    int countAccountSummaries(DomesticInvestmentSearchDTO condition);

    Optional<DomesticInvestmentListDTO> selectAccountSummaryById(
            @Param("accountId") Long accountId);

    List<DomesticHoldingDTO> selectHoldingsByAccountId(@Param("accountId") Long accountId);

    DomesticInvestmentSummaryDTO selectSummaryStats(@Param("sinceDate") LocalDate sinceDate);

    List<DomesticFundHoldingDetailDTO> selectActiveFundHoldings();

    List<DomesticRestrictedHoldingDTO> selectRestrictedHoldings();

    default List<DomesticCashHeavyAccountDTO> selectCashHeavyAccounts(int offset, int size) {
        return selectCashHeavyAccountsOrdered(DEFAULT_CASH_HEAVY_ORDER_BY, offset, size);
    }

    String DEFAULT_CASH_HEAVY_ORDER_BY =
            "(a.amount / NULLIF(a.amount + COALESCE(SUM(dsb.qty * dsb.avg_purchase_price), 0), 0))"
                    + " DESC";

    /** orderBy 는 OrderBySpec 이 만든 안전한 문자열만 넘긴다. */
    List<DomesticCashHeavyAccountDTO> selectCashHeavyAccountsOrdered(
            @Param("orderBy") String orderBy, @Param("offset") int offset, @Param("size") int size);

    int countCashHeavyAccounts();

    List<DomesticAccountLiteDTO> selectAccountsByRecentBuyStatus(
            @Param("sinceDate") LocalDate sinceDate, @Param("hasRecentBuy") boolean hasRecentBuy);
}
