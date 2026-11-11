package com.app.maria.domain.risk.mapper;

import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockRiskGradeMapper {
    Integer sessionUtcOffsetSeconds();

    List<com.app.maria.domain.risk.dto.RiskProductDTO> findProductsByIds(
            @Param("ids") java.util.Collection<Long> ids);

    com.app.maria.domain.risk.dto.RiskProductDTO findProductByTicker(
            @Param("ticker") String ticker);

    List<Long> lockProductsByTicker(@Param("ticker") String ticker);

    StockRiskGradeDTO findForUpdate(@Param("id") long id);

    List<StockRiskGradeDTO> findAll();

    StockRiskGradeDTO findByTicker(@Param("ticker") String ticker);

    int insert(StockRiskGradeDTO row);

    int updateIfNewer(StockRiskGradeDTO row);
}
