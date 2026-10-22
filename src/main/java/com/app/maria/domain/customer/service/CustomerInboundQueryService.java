package com.app.maria.domain.customer.service;

import com.app.maria.domain.customer.dto.response.CustomerForeignProductResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundCandidatesResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundDetailResponseDTO;
import com.app.maria.domain.customer.dto.response.CustomerInboundHistoryResponseDTO;
import com.app.maria.global.response.PageResponseDTO;

import java.util.List;

public interface CustomerInboundQueryService {
    List<CustomerForeignProductResponseDTO> getForeignProducts(Long customerId);

    CustomerInboundCandidatesResponseDTO getCandidates(Long customerId, Long foreignProductId);

    PageResponseDTO<CustomerInboundHistoryResponseDTO> getHistory(Long customerId, int page, int size);

    CustomerInboundDetailResponseDTO getDetail(Long customerId, Long inboundId);
}
