package com.app.maria.domain.account.dto.request;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 고객 식별자는 요청 본문이 아닌 로그인 세션에서 가져온다. */
@Getter
@Setter
@NoArgsConstructor
public class CustomerAccountApplyRequestDTO {
    @AccountLimit private BigDecimal limitAmount;
}
