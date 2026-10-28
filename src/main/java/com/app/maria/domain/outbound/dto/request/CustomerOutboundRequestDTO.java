package com.app.maria.domain.outbound.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;

@Getter
public class CustomerOutboundRequestDTO {
    @NotNull private Long foreignProductId;

    @NotNull
    @DecimalMin(value = "0.0001")
    @Digits(integer = 11, fraction = 4)
    private BigDecimal qty;
}
