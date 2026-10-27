package com.app.maria.domain.inbound.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CustomerInboundSubmitRequestDTO {

    @NotNull private Long foreignProductId;

    @NotNull private Long sourceGeneralAccountId;

    @NotNull
    @Positive
    @Digits(integer = 11, fraction = 4)
    private BigDecimal requestedQty;
}
