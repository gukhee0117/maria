package com.app.maria.domain.asset.mapper;

import com.app.maria.domain.asset.dto.AssetHoldingDTO;
import com.app.maria.domain.asset.dto.AssetMonthlyPurchaseDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AssetBriefingMapper {
    BigDecimal sumHoldingCost(@Param("accountId") Long accountId);

    List<AssetHoldingDTO> selectHoldings(@Param("accountId") Long accountId);

    BigDecimal sumFinalizedInflow(
            @Param("accountId") Long accountId, @Param("asOf") LocalDateTime asOf);

    List<AssetMonthlyPurchaseDTO> selectMonthlyPurchases(
            @Param("accountId") Long accountId, @Param("asOf") LocalDateTime asOf);
}
