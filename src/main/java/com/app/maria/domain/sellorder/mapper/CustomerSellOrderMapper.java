package com.app.maria.domain.sellorder.mapper;

import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerSellOrderMapper {

    /** 접수 시각 최신순. */
    List<CustomerSellOrderResponseDTO> selectByAccountId(
            @Param("accountId") Long accountId,
            @Param("offset") long offset,
            @Param("size") int size);

    int countByAccountId(@Param("accountId") Long accountId);
}
