package com.app.maria.domain.sellorder.mapper;

import com.app.maria.domain.sellorder.dto.SellOrderDTO;
import com.app.maria.domain.sellorder.dto.SellOrderDetailDTO;
import com.app.maria.domain.sellorder.dto.SellOrderHistoryDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SellOrderMapper {

    int insertSellOrder(SellOrderDTO dto);

    Optional<SellOrderDTO> selectSellOrderById(@Param("orderId") Long orderId);

    List<SellOrderDTO> selectSellOrdersByAccountId(@Param("accountId") Long accountId);

    List<SellOrderDTO> selectSellOrdersByInboundDetailIds(
            @Param("inboundDetailIds") List<Long> inboundDetailIds);

    BigDecimal sumSellAmountBetween(LocalDateTime start, LocalDateTime end);

    List<SellOrderHistoryDTO> selectSellOrderHistory(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("orderBy") String orderBy,
            @Param("offset") int offset,
            @Param("size") int size);

    int countSellOrderHistory(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    Optional<SellOrderDetailDTO> selectSellOrderDetail(@Param("orderId") Long orderId);
}
