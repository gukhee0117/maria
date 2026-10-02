package com.app.maria.global.client.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.global.error.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KisExchangeCodeTest {

    @Test
    @DisplayName("NASDAQ은 KIS 축약코드 NAS로 변환된다")
    void fromMarketConvertsNasdaqToNas() {
        assertThat(KisExchangeCode.fromMarket("NASDAQ")).isEqualTo("NAS");
    }

    @Test
    @DisplayName("NYSE는 KIS 축약코드 NYS로 변환된다")
    void fromMarketConvertsNyseToNys() {
        assertThat(KisExchangeCode.fromMarket("NYSE")).isEqualTo("NYS");
    }

    @Test
    @DisplayName("HKEX는 KIS 축약코드 HKS로 변환된다")
    void fromMarketConvertsHkexToHks() {
        assertThat(KisExchangeCode.fromMarket("HKEX")).isEqualTo("HKS");
    }

    @Test
    @DisplayName("TSE는 KIS 축약코드 TSE로 변환된다")
    void fromMarketConvertsTseToTse() {
        assertThat(KisExchangeCode.fromMarket("TSE")).isEqualTo("TSE");
    }

    @Test
    @DisplayName("대소문자가 섞여있거나 앞뒤 공백이 있어도 정상 매칭된다")
    void fromMarketIsCaseInsensitiveAndTrimsWhitespace() {
        assertThat(KisExchangeCode.fromMarket("  nasdaq  ")).isEqualTo("NAS");
    }

    @Test
    @DisplayName("DB의 market 값(풀네임)이 아니라 KIS 축약코드 자체를 넣으면 매칭 실패로 예외를 던진다")
    void fromMarketThrowsWhenGivenKisCodeInsteadOfDbMarketValue() {
        assertThatThrownBy(() -> KisExchangeCode.fromMarket("NAS"))
                .isInstanceOf(AppException.class)
                .hasMessage("지원하지 않는 거래소입니다.");
    }

    @Test
    @DisplayName("지원하지 않는 거래소면 예외를 던진다")
    void fromMarketThrowsWhenMarketIsUnsupported() {
        assertThatThrownBy(() -> KisExchangeCode.fromMarket("LSE"))
                .isInstanceOf(AppException.class)
                .hasMessage("지원하지 않는 거래소입니다.");
    }
}
