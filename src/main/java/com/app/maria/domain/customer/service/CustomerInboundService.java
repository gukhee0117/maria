package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundSubmitResponseDTO;

public interface CustomerInboundService {
    CustomerInboundSubmitResponseDTO submit(
            Long customerId, CustomerInboundSubmitRequestDTO request);
}
