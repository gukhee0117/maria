package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.global.response.PageResponseDTO;

public interface CustomerSellOrderService {

    PageResponseDTO<CustomerSellOrderResponseDTO> getSellOrders(
            Long customerId, int page, int size);
}
