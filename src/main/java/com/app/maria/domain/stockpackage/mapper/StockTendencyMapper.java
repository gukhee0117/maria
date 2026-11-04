package com.app.maria.domain.stockpackage.mapper;

import com.app.maria.domain.stockpackage.vo.TradePeriodVO;
import com.app.maria.domain.stockpackage.vo.TradeProductSummaryVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 거래에서 본 경향 계산에 쓰는 RIA 국내 주문 원장 조회. 체결(EXECUTED)된 주문만 센다. */
@Mapper
public interface StockTendencyMapper {
    List<TradeProductSummaryVO> selectTradeSummariesByCustomerId(
            @Param("customerId") Long customerId);

    TradePeriodVO selectTradePeriodByCustomerId(@Param("customerId") Long customerId);
}
