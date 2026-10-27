package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.global.config.properties.CiProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CiHasherTest {
    // 시드 생성기(seed/identity/CiHasher)와 같은 산식으로 계산한 기준값
    private static final String RRN = "9001011234567";
    private static final String EXPECTED =
            "c7e646ba0c1b65af86b8c478da9c19048d69a17c019f5e8ad41b1db4e93140d2";

    private final CiHasher hasher = hasher("test-pepper");

    private static CiHasher hasher(String pepper) {
        CiProperties properties = new CiProperties();
        properties.setPepper(pepper);
        return new CiHasher(properties);
    }

    @Test
    @DisplayName("시드 생성기와 같은 산식으로 ci_hash를 만든다")
    void matchesSeedGeneratorValue() {
        assertThat(hasher.of(RRN)).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("하이픈이 있어도 숫자만 남겨 같은 값을 만든다")
    void ignoresNonDigits() {
        assertThat(hasher.of("900101-1234567")).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("결과는 소문자 hex 64자다")
    void producesLowercaseHex64() {
        assertThat(hasher.of(RRN)).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("pepper가 다르면 값이 다르다")
    void differentPepperGivesDifferentHash() {
        assertThat(hasher("other-pepper").of(RRN)).isNotEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("pepper가 비어 있으면 설정 검증에 걸린다")
    void rejectsBlankPepper() {
        CiProperties properties = new CiProperties();
        properties.setPepper(" ");

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(properties))
                .hasSize(1);
    }
}
