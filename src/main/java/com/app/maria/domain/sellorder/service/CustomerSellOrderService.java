package com.app.maria.domain.sellorder.service;

import com.app.maria.domain.sellorder.dto.request.CustomerSellOrderRequestDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResponseDTO;
import com.app.maria.domain.sellorder.dto.response.CustomerSellOrderResultResponseDTO;
import com.app.maria.global.response.PageResponseDTO;

public interface CustomerSellOrderService {

    /** 본인 RIA 계좌로 한 종목을 총수량만큼 매도 신청한다. 입고 lot은 서버가 FIFO로 나눈다. */
    CustomerSellOrderResultResponseDTO placeOrder(
            Long customerId, CustomerSellOrderRequestDTO request);

    PageResponseDTO<CustomerSellOrderResponseDTO> getSellOrders(
            Long customerId, int page, int size);
}
