package com.app.maria.domain.notification.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.notification.service.CustomerNotificationSettingsService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerNotificationSettingsApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerNotificationSettingsApiTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private CustomerNotificationSettingsService service;

    @Test
    void emailEnableWithoutRegisteredAddressReturnsActionableError() throws Exception {
        when(service.update(eq(45L), any()))
                .thenThrow(new AppException(ErrorType.CUSTOMER_NOTIFICATION_EMAIL_REQUIRED));
        mvc.perform(
                        put("/api/customer/notification-settings")
                                .with(
                                        authentication(
                                                new UsernamePasswordAuthenticationToken(
                                                        new CustomerPrincipal(
                                                                45L, "user000045", "고객"),
                                                        null,
                                                        List.of(
                                                                new SimpleGrantedAuthority(
                                                                        "ROLE_CUSTOMER")))))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"push":true,"sms":false,"email":true,"settle":true,"band":true,"reply":true,"kiru":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOTIFICATION_EMAIL_REQUIRED"))
                .andExpect(jsonPath("$.message").value("등록된 이메일이 없습니다. 기본 정보에서 이메일을 등록해 주세요."));
    }
}
