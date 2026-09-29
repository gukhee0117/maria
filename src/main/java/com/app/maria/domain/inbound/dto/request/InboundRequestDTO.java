package com.app.maria.domain.inbound.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class InboundRequestDTO {

    @NotNull(message = "accountId는 필수입니다.")
    private Long accountId;

    @NotNull(message = "foreignProductId는 필수입니다.")
    private Long foreignProductId;

    @NotNull(message = "requestedQty는 필수입니다.")
    @PositiveOrZero(message = "requestedQty는 0 이상이어야 합니다.")
    private BigDecimal requestedQty;

    @PositiveOrZero(message = "currentHoldingAtRequest는 0 이상이어야 합니다.")
    private BigDecimal currentHoldingAtRequest;
}
