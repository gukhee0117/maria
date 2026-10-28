package com.app.maria.domain.outbound.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class OutboundDTO {
    private Long outboundId;
    private Long inboundDetailId;
    private BigDecimal qty;
    private String reason;
    private LocalDateTime processedAt;
}
