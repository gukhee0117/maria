package com.app.maria.domain.settlement.notification;

import com.app.maria.domain.settlement.dto.response.SettlementNotificationResponseDTO;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SettlementSseServiceImpl implements SettlementSseService {

    static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<String, SseEmitter> adminEmitters = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, SseEmitter>> accountEmitters = new ConcurrentHashMap<>();
    private final Supplier<SseEmitter> emitterFactory;

    public SettlementSseServiceImpl() {
        this(() -> new SseEmitter(SSE_TIMEOUT_MILLIS));
    }

    SettlementSseServiceImpl(Supplier<SseEmitter> emitterFactory) {
        this.emitterFactory = emitterFactory;
    }

    @Override
    public SseEmitter subscribeAdmin() {
        String emitterId = UUID.randomUUID().toString();
        SseEmitter emitter = emitterFactory.get();
        adminEmitters.put(emitterId, emitter);
        Runnable remove = () -> adminEmitters.remove(emitterId);
        registerCallbacks(emitter, remove);
        sendConnectedEvent(emitter, remove);
        return emitter;
    }

    @Override
    public SseEmitter subscribeAccount(Long accountId) {
        String emitterId = UUID.randomUUID().toString();
        SseEmitter emitter = emitterFactory.get();
        Map<String, SseEmitter> emitters =
                accountEmitters.computeIfAbsent(accountId, ignored -> new ConcurrentHashMap<>());
        emitters.put(emitterId, emitter);
        Runnable remove = () -> removeAccountEmitter(accountId, emitterId);
        registerCallbacks(emitter, remove);
        sendConnectedEvent(emitter, remove);
        return emitter;
    }

    @Override
    public void sendBatchCompleted(SettlementNotificationResponseDTO notification) {
        adminEmitters.forEach(
                (emitterId, emitter) ->
                        sendNotification(
                                emitter, notification, () -> adminEmitters.remove(emitterId)));
    }

    @Override
    public void sendAccountBatchCompleted(
            Long accountId, SettlementNotificationResponseDTO notification) {
        Map<String, SseEmitter> emitters = accountEmitters.get(accountId);
        if (emitters == null) {
            return;
        }
        emitters.forEach(
                (emitterId, emitter) ->
                        sendNotification(
                                emitter,
                                notification,
                                () -> removeAccountEmitter(accountId, emitterId)));
    }

    private void sendConnectedEvent(SseEmitter emitter, Runnable remove) {
        try {
            emitter.send(SseEmitter.event().name("connected").data("connected"));
        } catch (IOException | IllegalStateException exception) {
            remove.run();
        }
    }

    private void sendNotification(
            SseEmitter emitter, SettlementNotificationResponseDTO notification, Runnable remove) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("settlement-batch-completed")
                            .id(notification.batchId().toString())
                            .data(notification));
        } catch (IOException | IllegalStateException exception) {
            remove.run();
        }
    }

    private void registerCallbacks(SseEmitter emitter, Runnable remove) {
        emitter.onCompletion(remove);
        emitter.onTimeout(emitter::complete);
        emitter.onError(exception -> remove.run());
    }

    private void removeAccountEmitter(Long accountId, String emitterId) {
        accountEmitters.computeIfPresent(
                accountId,
                (ignored, emitters) -> {
                    emitters.remove(emitterId);
                    return emitters.isEmpty() ? null : emitters;
                });
    }
}
