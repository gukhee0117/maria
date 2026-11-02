package com.app.maria.domain.community.type;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KiruStatusTest {

    @Test
    @DisplayName("처음 호출은 PENDING 또는 OUT_OF_SCOPE로만 시작한다")
    void firstCall() {
        assertThat(KiruStatus.canMove(null, KiruStatus.PENDING)).isTrue();
        assertThat(KiruStatus.canMove(null, KiruStatus.OUT_OF_SCOPE)).isTrue();
        assertThat(KiruStatus.canMove(null, KiruStatus.ANSWERED)).isFalse();
        assertThat(KiruStatus.canMove(null, KiruStatus.FAILED)).isFalse();
        assertThat(KiruStatus.canMove(null, KiruStatus.NO_SOURCE)).isFalse();
    }

    @Test
    @DisplayName("PENDING은 결과 3가지로만 끝난다")
    void pendingEnds() {
        assertThat(KiruStatus.canMove(KiruStatus.PENDING, KiruStatus.ANSWERED)).isTrue();
        assertThat(KiruStatus.canMove(KiruStatus.PENDING, KiruStatus.NO_SOURCE)).isTrue();
        assertThat(KiruStatus.canMove(KiruStatus.PENDING, KiruStatus.FAILED)).isTrue();
        assertThat(KiruStatus.canMove(KiruStatus.PENDING, KiruStatus.OUT_OF_SCOPE)).isFalse();
        assertThat(KiruStatus.canMove(KiruStatus.PENDING, KiruStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("FAILED만 다시 PENDING으로 돌아갈 수 있고 나머지 끝 상태는 고정이다")
    void onlyFailedRetries() {
        assertThat(KiruStatus.canMove(KiruStatus.FAILED, KiruStatus.PENDING)).isTrue();
        assertThat(KiruStatus.canMove(KiruStatus.ANSWERED, KiruStatus.PENDING)).isFalse();
        assertThat(KiruStatus.canMove(KiruStatus.NO_SOURCE, KiruStatus.PENDING)).isFalse();
        assertThat(KiruStatus.canMove(KiruStatus.OUT_OF_SCOPE, KiruStatus.PENDING)).isFalse();
        assertThat(KiruStatus.canMove(KiruStatus.FAILED, KiruStatus.ANSWERED)).isFalse();
    }

    @Test
    @DisplayName("null로는 바꿀 수 없다")
    void neverToNull() {
        assertThat(KiruStatus.canMove(KiruStatus.PENDING, null)).isFalse();
        assertThat(KiruStatus.canMove(null, null)).isFalse();
    }
}
