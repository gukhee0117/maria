package com.app.maria.domain.community.kkiru;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 지금 처리 중인 끼루 호출의 메모리 목록(시작한 실제 시각 기록). 오래된 PENDING 정리 작업이 "다시 묻기"로 방금 PENDING이 된 행이나 처리 중인 행을 죽은
 * 호출로 오해하지 않게 하는 보호막이다. 인스턴스 메모리라 인스턴스가 여러 개면 서로의 호출을 모른다(지금은 maria 한 개).
 */
@Component
@RequiredArgsConstructor
public class KkiruInFlight {
    /** 호출 하나: commentId가 null이면 글 본문에서 부른 것. */
    public record CallKey(Long postId, Long commentId) {}

    private final Clock clock;
    private final Map<CallKey, Instant> startedAt = new ConcurrentHashMap<>();

    public void start(CallKey key) {
        startedAt.put(key, clock.instant());
    }

    public void end(CallKey key) {
        startedAt.remove(key);
    }

    /** maxAge 안에 시작한 글 본문 호출의 글 id. 더 오래된 것은 죽은 작업으로 보고 빼 준다. */
    public Set<Long> postIds(Duration maxAge) {
        return alive(maxAge)
                .filter(k -> k.commentId() == null)
                .map(CallKey::postId)
                .collect(Collectors.toSet());
    }

    /** maxAge 안에 시작한 댓글 호출의 댓글 id. */
    public Set<Long> commentIds(Duration maxAge) {
        return alive(maxAge)
                .filter(k -> k.commentId() != null)
                .map(CallKey::commentId)
                .collect(Collectors.toSet());
    }

    private java.util.stream.Stream<CallKey> alive(Duration maxAge) {
        Instant limit = clock.instant().minus(maxAge);
        return startedAt.entrySet().stream()
                .filter(e -> e.getValue().isAfter(limit))
                .map(Map.Entry::getKey);
    }
}
