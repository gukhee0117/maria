package com.app.maria.domain.stockpackage.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 패키지 점검 요청. 저장하지 않은 구성도 점검할 수 있어 패키지 ID가 아니라 종목·비중을 받는다. */
public record StockPackageCheckRequestDTO(
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items) {

    public record Item(
            @NotNull Long domesticProductId,
            @NotNull @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
                    BigDecimal weight) {}
}
