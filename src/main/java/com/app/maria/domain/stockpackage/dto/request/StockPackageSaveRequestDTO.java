package com.app.maria.domain.stockpackage.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 패키지 생성·수정 요청. 고객 ID와 확인 시각은 서버가 채우므로 받지 않는다. 수정은 구성 전체를 다시 보내는 방식이다. 종목이 없는 빈 패키지는 items를 빈 배열로
 * 보낸다.
 */
public record StockPackageSaveRequestDTO(
        @NotBlank @Size(max = 20) String name,
        @Positive @Digits(integer = 15, fraction = 0) BigDecimal totalAmount,
        @NotNull @Size(max = 50) List<@NotNull @Valid ItemRequest> items) {

    /** unsuitableConfirmed는 내 성향보다 위험한 종목을 알고도 담겠다고 고객이 확인했는지다. */
    public record ItemRequest(
            @NotNull Long domesticProductId,
            @NotNull @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
                    BigDecimal weight,
            boolean unsuitableConfirmed) {}
}
