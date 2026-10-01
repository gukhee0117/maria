package com.app.maria.global.client.kis;

import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KisExchangeCode {
    NASDAQ("NAS"),
    NYSE("NYS"),
    HKEX("HKS"),
    TSE("TSE");

    private final String kisCode;

    public static String fromMarket(String market) {
        for (KisExchangeCode code : values()) {
            if (code.name().equalsIgnoreCase(market.trim())) {
                return code.getKisCode();
            }
        }

        throw new AppException(ErrorType.UNSUPPORTED_EXCHANGE, market);
    }
}
