package com.app.maria.domain.asset.service;

import com.app.maria.global.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 접속 중인 고객만 확인한다. 외부 호출 중 DB 트랜잭션을 유지하지 않는다. */
@Service
public class AssetEventService {
    private final AssetBriefingService briefingService;
    private final ObjectMapper objectMapper;
    private final Supplier<SseEmitter> emitterFactory;
    private final Map<Long, Subscribers> customers = new ConcurrentHashMap<>();
    private final AtomicInteger pollCount = new AtomicInteger();
    private ThreadPoolTaskScheduler scheduler;

    @Value("${custom.asset.events.poll-ms:2000}")
    private long pollIntervalMs;

    @Value("${custom.asset.events.reconnect-ms:2000}")
    private long reconnectIntervalMs;

    @Value("${custom.asset.events.timeout-ms:60000}")
    private long timeoutMs;

    @Autowired
    public AssetEventService(AssetBriefingService briefingService, ObjectMapper objectMapper) {
        this(briefingService, objectMapper, null);
    }

    AssetEventService(
            AssetBriefingService briefingService,
            ObjectMapper objectMapper,
            Supplier<SseEmitter> emitterFactory) {
        this.briefingService = briefingService;
        this.objectMapper = objectMapper;
        this.emitterFactory = emitterFactory;
    }

    public synchronized SseEmitter subscribe(Long customerId) {
        Subscribers group = customers.computeIfAbsent(customerId, ignored -> new Subscribers());
        String id = UUID.randomUUID().toString();
        SseEmitter emitter =
                emitterFactory == null
                        ? new SseEmitter(Math.max(1000, timeoutMs))
                        : emitterFactory.get();
        group.emitters.put(id, emitter);
        Runnable remove = () -> remove(customerId, group, id);
        emitter.onCompletion(remove);
        emitter.onTimeout(
                () -> {
                    remove.run();
                    emitter.complete();
                });
        emitter.onError(error -> remove.run());
        send(customerId, group, id, emitter, "connected");
        return emitter;
    }

    @PostConstruct
    public void startPolling() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("asset-events-");
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(
                this::poll, Duration.ofMillis(Math.max(100, pollIntervalMs)));
    }

    @PreDestroy
    public void stopPolling() {
        if (scheduler != null) scheduler.shutdown();
        customers.values().forEach(group -> group.emitters.values().forEach(SseEmitter::complete));
        customers.clear();
    }

    public void poll() {
        boolean heartbeat = pollCount.incrementAndGet() % 5 == 0;
        customers.forEach(
                (customerId, group) -> {
                    if (group.emitters.isEmpty()) return;
                    String fingerprint;
                    try {
                        ObjectNode data =
                                objectMapper.valueToTree(briefingService.getBriefing(customerId));
                        data.remove("calculatedAt");
                        fingerprint = data.toString();
                    } catch (AppException e) {
                        fingerprint = "error:" + e.getErrorType().name();
                    } catch (RuntimeException e) {
                        fingerprint = "error:unavailable";
                    }
                    if (!fingerprint.equals(group.fingerprint)) {
                        group.fingerprint = fingerprint;
                        broadcast(customerId, group, "asset-changed");
                    } else if (heartbeat) {
                        broadcast(customerId, group, "heartbeat");
                    }
                });
    }

    private void broadcast(Long customerId, Subscribers group, String event) {
        group.emitters.forEach((id, emitter) -> send(customerId, group, id, emitter, event));
    }

    private void send(
            Long customerId, Subscribers group, String id, SseEmitter emitter, String event) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(event)
                            .reconnectTime(Math.max(100, reconnectIntervalMs))
                            .data("refresh"));
        } catch (IOException | IllegalStateException e) {
            remove(customerId, group, id);
            emitter.complete();
        }
    }

    private synchronized void remove(Long customerId, Subscribers group, String id) {
        group.emitters.remove(id);
        if (group.emitters.isEmpty()) customers.remove(customerId, group);
    }

    private static class Subscribers {
        private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
        private volatile String fingerprint;
    }
}
