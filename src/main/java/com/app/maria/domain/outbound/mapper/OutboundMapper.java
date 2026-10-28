package com.app.maria.domain.outbound.mapper;

import com.app.maria.domain.outbound.dto.OutboundDTO;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboundMapper {
    void insertOutbound(OutboundDTO outboundDTO);

    BigDecimal sumOutboundQtyByInboundDetail(@Param("inboundDetailId") Long inboundDetailId);
}
