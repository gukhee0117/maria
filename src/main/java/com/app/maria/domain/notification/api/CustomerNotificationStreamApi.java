package com.app.maria.domain.notification.api;

import com.app.maria.domain.notification.service.CustomerNotificationStream;
import com.app.maria.global.security.customer.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class CustomerNotificationStreamApi {
    private final CustomerNotificationStream stream;

    @GetMapping(
            value = "/api/customer/notifications/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return stream.subscribe(principal.customerId());
    }
}
