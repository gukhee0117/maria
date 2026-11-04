package com.app.maria.domain.settlement.service;

import com.app.maria.domain.settlement.dto.response.CustomerSettlementResponseDTO;
import com.app.maria.global.response.PageResponseDTO;

public interface CustomerSettlementService {

    PageResponseDTO<CustomerSettlementResponseDTO> getSettlements(
            Long customerId, int page, int size);
}
