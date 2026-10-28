package com.app.maria.domain.outbound.service;

import com.app.maria.domain.outbound.dto.request.CustomerOutboundRequestDTO;
import com.app.maria.domain.outbound.dto.response.CustomerOutboundResponseDTO;

public interface CustomerOutboundService {
    CustomerOutboundResponseDTO submit(Long customerId, CustomerOutboundRequestDTO request);
}
