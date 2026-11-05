package com.app.maria.domain.domestic.service;

import com.app.maria.domain.domestic.dto.response.CustomerDomesticTradeResponseDTO;
import com.app.maria.global.response.PageResponseDTO;

public interface CustomerDomesticTradeService {

    PageResponseDTO<CustomerDomesticTradeResponseDTO> getTrades(
            Long customerId, int page, int size);
}
