package com.app.maria.domain.inbound.service;

import com.app.maria.domain.inbound.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundSubmitResponseDTO;

public interface CustomerInboundService {
    CustomerInboundSubmitResponseDTO submit(
            Long customerId, CustomerInboundSubmitRequestDTO request);
}
