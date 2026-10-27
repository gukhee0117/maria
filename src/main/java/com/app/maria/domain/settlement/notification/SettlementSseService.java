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
public class SettlementSseService {

    static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final Map<String, SseEmitter> adminEmitters = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, SseEmitter>> accountEmitters = new ConcurrentHashMap<>();
    private final Supplier<SseEmitter> emitterFactory;

    public SettlementSseService() {
        this(() -> new SseEmitter(SSE_TIMEOUT_MILLIS));
    }

    SettlementSseService(Supplier<SseEmitter> emitterFactory) {
        this.emitterFactory = emitterFactory;
    }

    public SseEmitter subscribeAdmin() {
        String emitterId = UUID.randomUUID().toString();
        SseEmitter emitter = emitterFactory.get();
        adminEmitters.put(emitterId, emitter);
        registerCallbacks(emitter, () -> adminEmitters.remove(emitterId));
        sendConnectedEvent(emitter, () -> adminEmitters.remove(emitterId));
        return emitter;
    }

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

    public void sendSettlementCompleted(SettlementNotificationResponseDTO notification) {
        adminEmitters.forEach(
                (emitterId, emitter) ->
                        sendNotification(
                                emitter, notification, () -> adminEmitters.remove(emitterId)));

        Map<String, SseEmitter> emitters = accountEmitters.get(notification.accountId());
        if (emitters != null) {
            emitters.forEach(
                    (emitterId, emitter) ->
                            sendNotification(
                                    emitter,
                                    notification,
                                    () ->
                                            removeAccountEmitter(
                                                    notification.accountId(), emitterId)));
        }
    }

    private void sendConnectedEvent(SseEmitter emitter, Runnable remove) {
        try {
            emitter.send(SseEmitter.event().name("connected").data("connected"));
        } catch (IOException | IllegalStateException exception) {
            remove.run();
            emitter.completeWithError(exception);
        }
    }

    private void sendNotification(
            SseEmitter emitter, SettlementNotificationResponseDTO notification, Runnable remove) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("settlement-completed")
                            .id(notification.itemId().toString())
                            .data(notification));
        } catch (IOException | IllegalStateException exception) {
            remove.run();
            emitter.completeWithError(exception);
        }
    }

    private void registerCallbacks(SseEmitter emitter, Runnable remove) {
        emitter.onCompletion(remove);
        emitter.onTimeout(
                () -> {
                    remove.run();
                    emitter.complete();
                });
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
