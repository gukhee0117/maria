package com.app.maria.domain.outbound.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;

@Getter
public class CustomerOutboundRequestDTO {
    @NotNull private Long foreignProductId;

    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal qty;
}
