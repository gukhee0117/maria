package com.app.maria.domain.settlement.mapper;

import com.app.maria.domain.settlement.dto.response.CustomerSettlementResponseDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerSettlementMapper {

    /** 가정산 시각 최신순. */
    List<CustomerSettlementResponseDTO> selectByAccountId(
            @Param("accountId") Long accountId,
            @Param("offset") long offset,
            @Param("size") int size);

    int countByAccountId(@Param("accountId") Long accountId);
}
