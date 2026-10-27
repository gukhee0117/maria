package com.app.maria.domain.settlement.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.settlement.service.SettlementNotificationService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(CustomerSettlementNotificationApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerSettlementNotificationApiTest {

    private static final CustomerPrincipal PRINCIPAL =
            new CustomerPrincipal(3L, "customer@test.com", "테스트고객");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SettlementNotificationService settlementNotificationService;

    @Test
    void unauthenticatedCustomerCannotSubscribe() throws Exception {
        mockMvc.perform(get("/api/customer/settlement/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void subscribesUsingLoggedInCustomerId() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(settlementNotificationService.subscribeCustomer(3L)).thenReturn(emitter);

        mockMvc.perform(get("/api/customer/settlement/notifications").with(customerAuth()))
                .andExpect(status().isOk());

        verify(settlementNotificationService).subscribeCustomer(3L);
        emitter.complete();
    }

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }
}
