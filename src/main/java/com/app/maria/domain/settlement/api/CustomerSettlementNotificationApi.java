package com.app.maria.domain.settlement.api;

import com.app.maria.domain.settlement.service.SettlementNotificationService;
import com.app.maria.global.security.customer.CustomerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/customer/settlement")
public class CustomerSettlementNotificationApi {

    private final SettlementNotificationService settlementNotificationService;

    @GetMapping(value = "/notifications", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return settlementNotificationService.subscribeCustomer(principal.customerId());
    }
}
