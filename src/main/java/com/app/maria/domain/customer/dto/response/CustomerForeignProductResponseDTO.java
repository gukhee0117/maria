package com.app.maria.domain.customer.dto.response;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import lombok.Getter;

@Getter
public class CustomerForeignProductResponseDTO {
    private final Long foreignProductId;
    private final String ticker;
    private final String name;
    private final String market;
    private final String currency;

    public CustomerForeignProductResponseDTO(ForeignProductDTO product) {
        this.foreignProductId = product.getForeignProductId();
        this.ticker = product.getTicker();
        this.name = product.getName();
        this.market = product.getMarket();
        this.currency = product.getCurrency();
    }
}
