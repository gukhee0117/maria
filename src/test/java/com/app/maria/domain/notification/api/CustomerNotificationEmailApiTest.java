package com.app.maria.domain.notification.api;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.notification.service.NotificationEmailVerificationService;
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

@WebMvcTest({CustomerNotificationEmailApi.class, CustomerNotificationApi.class})
@Import(CustomerSecurityConfig.class)
class CustomerNotificationEmailApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean NotificationEmailVerificationService verification;

    @MockitoBean
    com.app.maria.domain.notification.service.CustomerNotificationService notifications;

    static final String URL = "/api/customer/notification-email/verification";

    RequestPostProcessor customer() {
        return authentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CustomerPrincipal(1L, "user", "name"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    void verificationRequiresCustomerAndCsrfAndUsesOnlyPrincipalId() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(post(URL).with(customer())).andExpect(status().isForbidden());
        mvc.perform(
                        post(URL + "/confirm")
                                .with(customer())
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"code\":\"123\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(verification);
        mvc.perform(post(URL + "?customerId=999").with(customer()).with(csrf()))
                .andExpect(status().isOk());
        verify(verification).send(1L);
        mvc.perform(
                        post(URL + "/confirm")
                                .with(customer())
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"code\":\"123456\",\"customerId\":999}"))
                .andExpect(status().isOk());
        verify(verification).confirm(1L, "123456");
    }

    @Test
    void visibleReadValidatesIdsAndScopesThemToSessionCustomer() throws Exception {
        mvc.perform(
                        patch("/api/customer/notifications/read")
                                .with(customer())
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"notificationIds\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        patch("/api/customer/notifications/read")
                                .with(customer())
                                .with(csrf())
                                .contentType("application/json")
                                .content("{\"notificationIds\":[10,11],\"customerId\":999}"))
                .andExpect(status().isOk());
        verify(notifications).markVisibleRead(1L, List.of(10L, 11L));
    }
}
