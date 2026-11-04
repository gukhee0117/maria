package com.app.maria.domain.domestic.mapper;

import com.app.maria.domain.domestic.dto.DomesticProductDTO;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DomesticProductMapper {
    Optional<DomesticProductDTO> selectById(@Param("domesticProductId") Long domesticProductId);

    /** ticker로 종목명 등을 한꺼번에 조회한다. 마스터에 없는 ticker는 결과에서 빠진다. */
    List<DomesticProductDTO> selectByTickers(@Param("tickers") Set<String> tickers);
}
