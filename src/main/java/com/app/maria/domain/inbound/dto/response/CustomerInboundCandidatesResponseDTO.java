package com.app.maria.domain.inbound.dto.response;

import java.util.List;
import lombok.Getter;

@Getter
public class CustomerInboundCandidatesResponseDTO {
    private final Long foreignProductId;
    private final String ticker;
    private final String name;
    private final List<CustomerInboundCandidateResponseDTO> candidates;

    public CustomerInboundCandidatesResponseDTO(
            Long foreignProductId,
            String ticker,
            String name,
            List<CustomerInboundCandidateResponseDTO> candidates) {
        this.foreignProductId = foreignProductId;
        this.ticker = ticker;
        this.name = name;
        this.candidates = candidates;
    }
}
