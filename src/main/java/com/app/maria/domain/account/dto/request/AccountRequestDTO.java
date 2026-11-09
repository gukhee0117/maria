package com.app.maria.domain.account.dto.request;

import com.app.maria.domain.account.dto.AccountDTO;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class AccountRequestDTO {
    @NotNull(message = "개설할 계좌의 사용자 정보는 필수입니다.")
    @Positive(message = "사용자 ID는 0보다 커야 합니다.")
    private Long customerId;

    @AccountLimit private BigDecimal limitAmount;

    public AccountDTO toAccountDTO() {
        return AccountDTO.builder().customerId(customerId).limitAmount(limitAmount).build();
    }
}
