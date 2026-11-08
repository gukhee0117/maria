package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.sellorder.dto.SellOrderDetailDTO;
import com.app.maria.domain.sellorder.dto.SellOrderHistoryDTO;
import com.app.maria.domain.sellorder.dto.SellOrderSummaryDTO;
import com.app.maria.domain.sellorder.dto.request.SellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.SellOrderResponseDTO;
import com.app.maria.domain.sellorder.type.SellOrderStatus;
import com.app.maria.global.response.PageResponseDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface SellOrderService {

    List<SellOrderResponseDTO> placeSellOrder(Long actorAdminId, SellOrderRequestDTO request);

    SellOrderResponseDTO getSellOrder(Long orderId);

    SellOrderDetailDTO getSellOrderDetail(Long orderId);

    List<SellOrderResponseDTO> getSellOrderByAccount(Long accountId);

    BigDecimal getTodaySellAmount();

    SellOrderSummaryDTO getSellOrderSummary();

    PageResponseDTO<SellOrderHistoryDTO> getSellOrderHistory(
            String keyword,
            SellOrderStatus status,
            LocalDate startDate,
            LocalDate endDate,
            String sort,
            String direction,
            int page,
            int size);
}
