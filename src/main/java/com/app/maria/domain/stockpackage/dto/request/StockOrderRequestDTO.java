package com.app.maria.domain.stockpackage.dto.request;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 패키지 시장가 주문. 체결가·수수료·거래세는 서버가 정하므로 받지 않는다. idempotencyKey는 화면에서 주문 한 번마다 새로 만든 값(UUID)이고, 같은 키로 다시
 * 보내면 새로 체결하지 않고 처음 결과를 돌려준다.
 */
public record StockOrderRequestDTO(
        @NotBlank @Size(max = 36) @Pattern(regexp = "[A-Za-z0-9-]+") String idempotencyKey,
        @NotEmpty @Size(max = 20) List<@NotNull @Valid Item> items) {

    /** qty는 정수 주만 받는다(소수는 서비스에서 거부). */
    public record Item(
            @NotNull Long domesticProductId,
            @NotNull DomesticOrderSide side,
            @NotNull @Positive BigDecimal qty) {}
}
