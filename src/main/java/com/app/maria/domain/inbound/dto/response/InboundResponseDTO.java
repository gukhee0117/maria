package com.app.maria.domain.inbound.dto.response;

import com.app.maria.domain.inbound.dto.InboundDTO;
import com.app.maria.domain.inbound.type.InboundZeroApprovalReason;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class InboundResponseDTO {
    private Long inboundId;
    private BigDecimal requestedQty;
    private BigDecimal snapshotQty;
    private BigDecimal currentHoldingAtRequest;
    private BigDecimal approvedQty;
    private InboundZeroApprovalReason zeroApprovalReason;
    private LocalDateTime processedAt;

    public static InboundResponseDTO of(
            InboundDTO inboundDTO,
            BigDecimal snapshotQty,
            InboundZeroApprovalReason zeroApprovalReason) {
        return InboundResponseDTO.builder()
                .inboundId(inboundDTO.getInboundId())
                .requestedQty(inboundDTO.getRequestedQty())
                .snapshotQty(snapshotQty)
                .currentHoldingAtRequest(inboundDTO.getCurrentHoldingAtRequest())
                .approvedQty(inboundDTO.getApprovedQty())
                .zeroApprovalReason(zeroApprovalReason)
                .processedAt(inboundDTO.getProcessedAt())
                .build();
    }
}
