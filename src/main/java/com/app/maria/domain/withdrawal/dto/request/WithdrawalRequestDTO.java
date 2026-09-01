package com.app.maria.domain.withdrawal.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class WithdrawalRequestDTO {
    @NotNull(message = "계좌 ID는 필수입니다.")
    @Positive(message = "계좌 ID는 0보다 커야 합니다.")
    private Long accountId;

    @NotNull(message = "인출 요청금액은 필수입니다.")
    @Positive(message = "인출 요청금액은 0보다 커야 합니다.")
    private BigDecimal requestedAmount;

    // 조기인출 동의 여부(default=false)
    private boolean earlyWithdrawalAgreed;

    @NotNull(message = "인출 목적지 일반계좌 ID는 필수입니다.")
    @Positive(message = "인출 목적지 일반계좌 ID는 0보다 커야 합니다.")
    private Long destinationGeneralAccountId;
}
