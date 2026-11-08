package com.app.maria.domain.notification.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class CustomerNotificationStream {
    private final Map<Long, Map<String, SseEmitter>> customers = new ConcurrentHashMap<>();
    private final Supplier<SseEmitter> factory;

    public CustomerNotificationStream() {
        this(() -> new SseEmitter(30L * 60 * 1000));
    }

    CustomerNotificationStream(Supplier<SseEmitter> factory) {
        this.factory = factory;
    }

    public SseEmitter subscribe(Long customerId) {
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = factory.get();
        customers
                .computeIfAbsent(customerId, ignored -> new ConcurrentHashMap<>())
                .put(id, emitter);
        Runnable remove =
                () ->
                        customers.computeIfPresent(
                                customerId,
                                (ignored, entries) -> {
                                    entries.remove(id);
                                    return entries.isEmpty() ? null : entries;
                                });
        emitter.onCompletion(remove);
        emitter.onTimeout(
                () -> {
                    remove.run();
                    emitter.complete();
                });
        emitter.onError(error -> remove.run());
        send(emitter, "connected", remove);
        return emitter;
    }

    public void changed(Long customerId) {
        Map<String, SseEmitter> entries = customers.get(customerId);
        if (entries == null) return;
        entries.forEach(
                (id, emitter) ->
                        send(
                                emitter,
                                "notification-changed",
                                () -> {
                                    customers.computeIfPresent(
                                            customerId,
                                            (ignored, current) -> {
                                                current.remove(id);
                                                return current.isEmpty() ? null : current;
                                            });
                                    emitter.complete();
                                }));
    }

    private void send(SseEmitter emitter, String name, Runnable remove) {
        try {
            emitter.send(SseEmitter.event().name(name).data("refresh"));
        } catch (IOException | IllegalStateException failure) {
            remove.run();
        }
    }
}
