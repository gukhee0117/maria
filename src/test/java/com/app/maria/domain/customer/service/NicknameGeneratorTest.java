package com.app.maria.domain.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NicknameGeneratorTest {
    @Test
    @DisplayName("시드와 같은 모양(형용사+동물+숫자 3~4자리)이고 컬럼 길이 20자 안이다")
    void matchesSeedShape() {
        for (int i = 0; i < 200; i++) {
            String nickname = NicknameGenerator.generate(n -> false);
            assertThat(nickname).matches("[가-힣]+\\d{3,4}");
            assertThat(nickname.length()).isLessThanOrEqualTo(20);
        }
    }

    @Test
    @DisplayName("이미 쓰는 닉네임이면 다른 것을 고른다")
    void skipsTakenNicknames() {
        AtomicInteger calls = new AtomicInteger();

        String nickname = NicknameGenerator.generate(n -> calls.incrementAndGet() <= 5);

        assertThat(calls.get()).isEqualTo(6);
        assertThat(nickname).isNotNull();
    }

    @Test
    @DisplayName("계속 겹치면 무한 반복하지 않고 예외로 끝낸다")
    void givesUpWhenEverythingIsTaken() {
        assertThatThrownBy(() -> NicknameGenerator.generate(n -> true))
                .isInstanceOf(IllegalStateException.class);
    }
}
