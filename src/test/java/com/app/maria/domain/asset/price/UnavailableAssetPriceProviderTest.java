package com.app.maria.domain.asset.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UnavailableAssetPriceProviderTest {
    @Test
    void productionDefaultDoesNotInventTestPrices() {
        assertThat(new UnavailableAssetPriceProvider().getPrices(Set.of("005930", "000660")))
                .isEmpty();
    }
}
