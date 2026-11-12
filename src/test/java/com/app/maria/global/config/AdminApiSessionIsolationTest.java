package com.app.maria.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.global.clock.api.SystemClockApi;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.clock.service.SystemClockManagementService;
import com.app.maria.global.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 관리자 API는 세션을 쓰지 않는다. 같은 호스트에서 고객이 로그인해 둔 JSESSIONID가 함께 와도 관리자 요청이 그 세션 ID를 바꾸거나 새 쿠키를 주면 안
 * 된다(바뀌면 고객이 로그아웃된다).
 */
@WebMvcTest(SystemClockApi.class)
@Import(SecurityConfig.class)
class AdminApiSessionIsolationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean SystemClockManagementService systemClockManagementService;
    @MockitoBean BusinessClockService businessClockService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("고객 세션이 같이 와도 관리자 API는 세션 ID를 바꾸지 않고 쿠키를 주지 않는다")
    void adminApiLeavesCustomerSessionAlone() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.get("type")).thenReturn("access");
        when(claims.getSubject()).thenReturn("4");
        when(claims.get("role", String.class)).thenReturn("ADMIN");
        when(jwtTokenProvider.parseClaims("admin-token")).thenReturn(claims);
        when(businessClockService.now()).thenReturn(LocalDateTime.of(2026, 11, 12, 10, 0));

        MockHttpSession customerSession = new MockHttpSession();
        customerSession.setAttribute("customer", "kept");
        String before = customerSession.getId();

        MvcResult result =
                mockMvc.perform(
                                get("/api/admin/system-clock")
                                        .session(customerSession)
                                        .cookie(new Cookie("access_token", "admin-token")))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(customerSession.getId()).isEqualTo(before);
        assertThat(customerSession.isInvalid()).isFalse();
        assertThat(customerSession.getAttribute("customer")).isEqualTo("kept");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
    }
}
