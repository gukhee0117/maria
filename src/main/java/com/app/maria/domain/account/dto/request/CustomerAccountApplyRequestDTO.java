package com.app.maria.domain.account.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 고객 식별자는 요청 본문이 아닌 로그인 세션에서 가져온다. */
@Getter
@Setter
@NoArgsConstructor
public class CustomerAccountApplyRequestDTO {
    @NotNull(message = "계좌 한도는 필수입니다.")
    @DecimalMin(value = "1", message = "계좌 한도는 1원 이상이어야 합니다.")
    @DecimalMax(value = "50000000", message = "계좌 한도는 5천만원 이하여야 합니다.")
    @Digits(integer = 8, fraction = 0, message = "계좌 한도는 원 단위 정수로 입력해야 합니다.")
    private BigDecimal limitAmount;
}
