package com.app.maria.domain.inbound.service;

import com.app.maria.domain.inbound.dto.InboundPageDTO;
import com.app.maria.domain.inbound.dto.request.InboundRequestDTO;
import com.app.maria.domain.inbound.dto.response.*;
import com.app.maria.global.response.PageResponseDTO;
import java.util.List;

public interface InboundService {
    InboundResponseDTO processInbound(Long actorAdminId, InboundRequestDTO request);

    List<AccountHoldingResponseDTO> getHoldings(Long accountId);

    InboundPageDTO getInbounds(int page, int size);

    InboundSummaryResponseDTO getSummary();

    PageResponseDTO<InboundAccountSummaryResponseDTO> getAccountsWithInbounds(
            int page, int size, String keyword);

    InboundPageDTO getInboundsByAccount(Long accountId, int page, int size);

    List<InboundPriorApprovalResponseDTO> getPriorApprovals(Long inboundId);
}
