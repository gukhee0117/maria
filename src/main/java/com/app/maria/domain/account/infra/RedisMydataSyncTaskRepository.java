package com.app.maria.domain.account.infra;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j
public class RedisMydataSyncTaskRepository {
    private static final String SCHEDULE_KEY = "mydata:ria:retry:schedule";
    private static final String TASK_KEY_PREFIX = "mydata:ria:retry:task:";
    private static final String LOCK_KEY_PREFIX = "mydata:ria:retry:lock:";
    private static final long[] RETRY_DELAYS_MILLIS = {60_000L, 300_000L, 900_000L, 3_600_000L};
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
    private static final DefaultRedisScript<Long> CLEAR_TASK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then redis.call('del', KEYS[1]); return redis.call('zrem', KEYS[2], ARGV[2]) else return 0 end",
                    Long.class);
    private static final DefaultRedisScript<Long> RESCHEDULE_TASK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then redis.call('set', KEYS[1], ARGV[2]); return redis.call('zadd', KEYS[2], ARGV[3], ARGV[4]) else return 0 end",
                    Long.class);
    private static final DefaultRedisScript<Long> ENQUEUE_TASK_SCRIPT =
            new DefaultRedisScript<>(
                    "redis.call('set', KEYS[1], ARGV[1]); return redis.call('zadd', KEYS[2], ARGV[2], ARGV[3])",
                    Long.class);
    private static final DefaultRedisScript<Long> REMOVE_ORPHAN_SCHEDULE_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('exists', KEYS[1]) == 0 then return redis.call('zrem', KEYS[2], ARGV[1]) else return 0 end",
                    Long.class);

    private final StringRedisTemplate redisTemplate;

    public void enqueue(Long accountId, String operation) {
        if (accountId == null) {
            log.error("MyData 동기화 작업 등록 실패: accountId가 없습니다. operation={}", operation);
            return;
        }
        try {
            String task = operation + ":0:" + UUID.randomUUID();
            redisTemplate.execute(
                    ENQUEUE_TASK_SCRIPT,
                    List.of(taskKey(accountId), SCHEDULE_KEY),
                    task,
                    Long.toString(System.currentTimeMillis()),
                    accountId.toString());
        } catch (RuntimeException exception) {
            log.error(
                    "MyData 동기화 작업 Redis 등록 실패: accountId={}, operation={}",
                    accountId,
                    operation,
                    exception);
        }
    }

    public List<ClaimedTask> claimDueTasks(int batchSize) {
        try {
            Set<String> accountIds =
                    redisTemplate
                            .opsForZSet()
                            .rangeByScore(
                                    SCHEDULE_KEY,
                                    Double.NEGATIVE_INFINITY,
                                    System.currentTimeMillis(),
                                    0,
                                    batchSize);

            if (accountIds == null || accountIds.isEmpty()) {
                return List.of();
            }

            return accountIds.stream()
                    .map(this::claim)
                    .flatMap(java.util.Optional::stream)
                    .toList();

        } catch (RedisConnectionFailureException exception) {
            log.warn("Redis 연결 불가 - MyData 재시도 작업을 건너뜁니다.");
            return List.of();

        } catch (RuntimeException exception) {
            log.error("MyData 재시도 큐 조회 실패", exception);
            return List.of();
        }
    }

    public void complete(ClaimedTask task) {
        executeSafely(
                "MyData 재시도 제거",
                task.accountId(),
                () ->
                        redisTemplate.execute(
                                CLEAR_TASK_SCRIPT,
                                List.of(taskKey(task.accountId()), SCHEDULE_KEY),
                                task.value(),
                                Long.toString(task.accountId())));
    }

    public void reschedule(ClaimedTask task, String operation) {
        int attemptCount = parseAttemptCount(task.value()) + 1;
        long nextRetryAt = System.currentTimeMillis() + retryDelay(attemptCount);
        String nextTask = operation + ":" + attemptCount + ":" + UUID.randomUUID();
        executeSafely(
                "MyData 재시도 등록",
                task.accountId(),
                () ->
                        redisTemplate.execute(
                                RESCHEDULE_TASK_SCRIPT,
                                List.of(taskKey(task.accountId()), SCHEDULE_KEY),
                                task.value(),
                                nextTask,
                                Long.toString(nextRetryAt),
                                Long.toString(task.accountId())));
    }

    public void release(ClaimedTask task) {
        executeSafely(
                "MyData 재시도 lock 해제",
                task.accountId(),
                () ->
                        redisTemplate.execute(
                                RELEASE_LOCK_SCRIPT, List.of(task.lockKey()), task.lockToken()));
    }

    private java.util.Optional<ClaimedTask> claim(String accountIdValue) {
        Long accountId = Long.parseLong(accountIdValue);
        String lockKey = LOCK_KEY_PREFIX + accountIdValue;
        String lockToken = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            if (!Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, LOCK_TTL))) {
                return java.util.Optional.empty();
            }
            locked = true;
            String value = redisTemplate.opsForValue().get(taskKey(accountId));
            if (value == null) {
                removeOrphanSchedule(accountIdValue);
                release(new ClaimedTask(accountId, "", lockKey, lockToken));
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ClaimedTask(accountId, value, lockKey, lockToken));
        } catch (RuntimeException exception) {
            if (locked) {
                release(new ClaimedTask(accountId, "", lockKey, lockToken));
            }
            log.error("MyData 재시도 작업 선점 실패: accountId={}", accountIdValue, exception);
            return java.util.Optional.empty();
        }
    }

    private void removeOrphanSchedule(String accountIdValue) {
        executeSafely(
                "MyData 고아 재시도 작업 제거",
                accountIdValue,
                () ->
                        redisTemplate.execute(
                                REMOVE_ORPHAN_SCHEDULE_SCRIPT,
                                List.of(TASK_KEY_PREFIX + accountIdValue, SCHEDULE_KEY),
                                accountIdValue));
    }

    private void executeSafely(String action, Object accountId, Runnable redisAction) {
        try {
            redisAction.run();
        } catch (RuntimeException exception) {
            log.error("{} 실패: accountId={}", action, accountId, exception);
        }
    }

    private String taskKey(Long accountId) {
        return TASK_KEY_PREFIX + accountId;
    }

    private int parseAttemptCount(String task) {
        try {
            return Integer.parseInt(task.split(":", 3)[1]);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private long retryDelay(int attemptCount) {
        return RETRY_DELAYS_MILLIS[Math.min(attemptCount - 1, RETRY_DELAYS_MILLIS.length - 1)];
    }

    public record ClaimedTask(Long accountId, String value, String lockKey, String lockToken) {}
}
