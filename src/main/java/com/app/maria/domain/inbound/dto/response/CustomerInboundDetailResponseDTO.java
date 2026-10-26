package com.app.maria.domain.inbound.dto.response;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.inbound.dto.InboundDTO;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;

@Getter
public class CustomerInboundDetailResponseDTO {
    private final Long inboundId;
    private final Long foreignProductId;
    private final String ticker;
    private final String name;
    private final Long sourceGeneralAccountId;
    private final BigDecimal requestedQty;
    private final BigDecimal approvedQty;
    private final String partialReason;
    private final LocalDateTime processedAt;
    private final List<CustomerInboundDetailItemResponseDTO> details;

    public CustomerInboundDetailResponseDTO(
            InboundDTO inbound, ForeignProductDTO product, List<InboundDetailDTO> detailDtos) {
        this.inboundId = inbound.getInboundId();
        this.foreignProductId = product != null ? product.getForeignProductId() : null;
        this.ticker = product != null ? product.getTicker() : null;
        this.name = product != null ? product.getName() : null;
        this.sourceGeneralAccountId =
                detailDtos.isEmpty() ? null : detailDtos.get(0).getSourceGeneralAccountId();
        this.requestedQty = inbound.getRequestedQty();
        this.approvedQty = inbound.getApprovedQty();
        this.partialReason =
                inbound.getApprovedQty().compareTo(inbound.getRequestedQty()) < 0
                        ? "BELOW_REQUEST"
                        : null;
        this.processedAt = inbound.getProcessedAt();
        this.details = detailDtos.stream().map(CustomerInboundDetailItemResponseDTO::new).toList();
    }
}
