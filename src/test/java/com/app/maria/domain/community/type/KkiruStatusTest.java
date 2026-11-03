package com.app.maria.domain.community.type;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KkiruStatusTest {

    @Test
    @DisplayName("처음 호출은 PENDING 또는 OUT_OF_SCOPE로만 시작한다")
    void firstCall() {
        assertThat(KkiruStatus.canMove(null, KkiruStatus.PENDING)).isTrue();
        assertThat(KkiruStatus.canMove(null, KkiruStatus.OUT_OF_SCOPE)).isTrue();
        assertThat(KkiruStatus.canMove(null, KkiruStatus.ANSWERED)).isFalse();
        assertThat(KkiruStatus.canMove(null, KkiruStatus.FAILED)).isFalse();
        assertThat(KkiruStatus.canMove(null, KkiruStatus.NO_SOURCE)).isFalse();
    }

    @Test
    @DisplayName("PENDING은 결과 3가지로만 끝난다")
    void pendingEnds() {
        assertThat(KkiruStatus.canMove(KkiruStatus.PENDING, KkiruStatus.ANSWERED)).isTrue();
        assertThat(KkiruStatus.canMove(KkiruStatus.PENDING, KkiruStatus.NO_SOURCE)).isTrue();
        assertThat(KkiruStatus.canMove(KkiruStatus.PENDING, KkiruStatus.FAILED)).isTrue();
        assertThat(KkiruStatus.canMove(KkiruStatus.PENDING, KkiruStatus.OUT_OF_SCOPE)).isFalse();
        assertThat(KkiruStatus.canMove(KkiruStatus.PENDING, KkiruStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("FAILED만 다시 PENDING으로 돌아갈 수 있고 나머지 끝 상태는 고정이다")
    void onlyFailedRetries() {
        assertThat(KkiruStatus.canMove(KkiruStatus.FAILED, KkiruStatus.PENDING)).isTrue();
        assertThat(KkiruStatus.canMove(KkiruStatus.ANSWERED, KkiruStatus.PENDING)).isFalse();
        assertThat(KkiruStatus.canMove(KkiruStatus.NO_SOURCE, KkiruStatus.PENDING)).isFalse();
        assertThat(KkiruStatus.canMove(KkiruStatus.OUT_OF_SCOPE, KkiruStatus.PENDING)).isFalse();
        assertThat(KkiruStatus.canMove(KkiruStatus.FAILED, KkiruStatus.ANSWERED)).isFalse();
    }

    @Test
    @DisplayName("null로는 바꿀 수 없다")
    void neverToNull() {
        assertThat(KkiruStatus.canMove(KkiruStatus.PENDING, null)).isFalse();
        assertThat(KkiruStatus.canMove(null, null)).isFalse();
    }
}
