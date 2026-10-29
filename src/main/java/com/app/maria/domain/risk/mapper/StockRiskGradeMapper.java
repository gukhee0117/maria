package com.app.maria.domain.risk.mapper;

import com.app.maria.domain.risk.dto.StockRiskGradeDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockRiskGradeMapper {
    Integer sessionUtcOffsetSeconds();

    List<Long> lockProductsByTicker(@Param("ticker") String ticker);

    StockRiskGradeDTO findForUpdate(@Param("id") long id);

    List<StockRiskGradeDTO> findAll();

    int insert(StockRiskGradeDTO row);

    int updateIfNewer(StockRiskGradeDTO row);
}
