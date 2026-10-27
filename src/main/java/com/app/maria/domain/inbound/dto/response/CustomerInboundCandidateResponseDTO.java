package com.app.maria.domain.inbound.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CustomerInboundCandidateResponseDTO {
    private final Long sourceGeneralAccountId;
    private final String accountNo;
    private final String brokerName;
    private final BigDecimal snapshotQty;
    private final BigDecimal alreadyApprovedQty;
    private final BigDecimal estimatedAvailableQty;
}
