package com.app.maria.domain.notification.service;

import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Notification-only registry; subscribing must not start the separate asset polling loop. */
@Service
public class CustomerNotificationStream {
    private static final int MAX_TABS = 5;
    private final Map<Long, LinkedHashMap<String, Subscription>> customers = new HashMap<>();
    private final Supplier<SseEmitter> factory;
    private final Executor writer;

    @Autowired
    public CustomerNotificationStream(@Qualifier("notificationStreamExecutor") Executor writer) {
        this(() -> new SseEmitter(30L * 60 * 1000), writer);
    }

    CustomerNotificationStream(Supplier<SseEmitter> factory) {
        this(factory, Runnable::run);
    }

    CustomerNotificationStream(Supplier<SseEmitter> factory, Executor writer) {
        this.factory = factory;
        this.writer = writer;
    }

    public SseEmitter subscribe(Long customerId) {
        return subscribe(customerId, null);
    }

    public synchronized SseEmitter subscribe(Long customerId, HttpSession session) {
        String id = UUID.randomUUID().toString();
        SseEmitter emitter = factory.get();
        var subscription = new Subscription(customerId, id, emitter, session);
        var entries = customers.computeIfAbsent(customerId, ignored -> new LinkedHashMap<>());
        Subscription evicted = null;
        if (entries.size() >= MAX_TABS) {
            String oldest = entries.keySet().iterator().next();
            evicted = entries.remove(oldest);
        }
        entries.put(id, subscription);
        emitter.onCompletion(() -> remove(subscription, false));
        emitter.onTimeout(() -> remove(subscription, true));
        emitter.onError(error -> remove(subscription, true));
        if (evicted != null) evicted.emitter().complete();
        dispatch(() -> send(subscription, "connected"));
        return emitter;
    }

    public void changed(Long customerId) {
        broadcast(snapshot(customerId), "notification-changed");
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        broadcast(snapshot(null), "heartbeat");
    }

    private void broadcast(List<Subscription> subscriptions, String event) {
        if (!subscriptions.isEmpty()) dispatch(() -> subscriptions.forEach(s -> send(s, event)));
    }

    private void dispatch(Runnable task) {
        try {
            writer.execute(task);
        } catch (RejectedExecutionException busy) {
            // A bounded queue protects committing threads; client polling recovers skipped signals.
        }
    }

    private synchronized List<Subscription> snapshot(Long customerId) {
        if (customerId != null)
            return new ArrayList<>(
                    customers.getOrDefault(customerId, new LinkedHashMap<>()).values());
        return customers.values().stream().flatMap(map -> map.values().stream()).toList();
    }

    private synchronized boolean registered(Subscription s) {
        return customers.containsKey(s.customerId())
                && customers.get(s.customerId()).get(s.id()) == s;
    }

    private void send(Subscription s, String event) {
        if (!registered(s)) return;
        if (!authorized(s)) {
            remove(s, true);
            return;
        }
        try {
            s.emitter().send(SseEmitter.event().name(event).reconnectTime(2000).data("refresh"));
        } catch (IOException | IllegalStateException failure) {
            remove(s, true);
        }
    }

    private boolean authorized(Subscription s) {
        if (s.session() == null)
            return true; // Unit factory; the authenticated API always supplies its session.
        try {
            Object value =
                    s.session()
                            .getAttribute(
                                    HttpSessionSecurityContextRepository
                                            .SPRING_SECURITY_CONTEXT_KEY);
            if (!(value instanceof SecurityContext context) || context.getAuthentication() == null)
                return false;
            return context.getAuthentication().isAuthenticated()
                    && context.getAuthentication().getPrincipal() instanceof CustomerPrincipal p
                    && p.customerId().equals(s.customerId());
        } catch (IllegalStateException expired) {
            return false;
        }
    }

    private synchronized void remove(Subscription s, boolean complete) {
        var entries = customers.get(s.customerId());
        if (entries != null) {
            entries.remove(s.id());
            if (entries.isEmpty()) customers.remove(s.customerId());
        }
        if (complete) s.emitter().complete();
    }

    @PreDestroy
    public void close() {
        var all = snapshot(null);
        synchronized (this) {
            customers.clear();
        }
        all.forEach(s -> s.emitter().complete());
    }

    private record Subscription(
            Long customerId, String id, SseEmitter emitter, HttpSession session) {}
}
