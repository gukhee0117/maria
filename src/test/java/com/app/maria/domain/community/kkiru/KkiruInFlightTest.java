package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KkiruInFlightTest {
    /** 손으로 옮기는 실제 시각. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T01:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private final MovableClock clock = new MovableClock();
    private final KkiruInFlight inFlight = new KkiruInFlight(clock);

    @Test
    @DisplayName("글 본문 호출은 글 id로, 댓글 호출은 댓글 id로 나뉘어 나온다")
    void splitsPostAndComment() {
        inFlight.start(new KkiruInFlight.CallKey(1L, null));
        inFlight.start(new KkiruInFlight.CallKey(2L, 20L));

        assertThat(inFlight.postIds(FIVE_MINUTES)).containsExactly(1L);
        assertThat(inFlight.commentIds(FIVE_MINUTES)).containsExactly(20L);
    }

    @Test
    @DisplayName("끝낸 호출은 빠진다")
    void endRemoves() {
        KkiruInFlight.CallKey key = new KkiruInFlight.CallKey(1L, null);
        inFlight.start(key);
        inFlight.end(key);

        assertThat(inFlight.postIds(FIVE_MINUTES)).isEmpty();
    }

    @Test
    @DisplayName("실제 5분을 넘긴 호출은 죽은 작업으로 보고 보호 목록에서 뺀다")
    void staleEntriesAreNotProtected() {
        inFlight.start(new KkiruInFlight.CallKey(1L, null));
        clock.advance(Duration.ofMinutes(4));
        assertThat(inFlight.postIds(FIVE_MINUTES)).containsExactly(1L);

        clock.advance(Duration.ofMinutes(1));
        assertThat(inFlight.postIds(FIVE_MINUTES)).isEmpty();
    }

    @Test
    @DisplayName("같은 호출을 다시 시작하면(다시 묻기) 시작 시각이 새로 잡힌다")
    void restartResetsAge() {
        KkiruInFlight.CallKey key = new KkiruInFlight.CallKey(1L, null);
        inFlight.start(key);
        clock.advance(Duration.ofMinutes(10));
        inFlight.start(key);

        assertThat(inFlight.postIds(FIVE_MINUTES)).containsExactly(1L);
    }
}
