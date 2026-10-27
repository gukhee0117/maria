package com.app.maria.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.admin.api.AdminAuthApi;
import com.app.maria.domain.admin.service.AdminService;
import com.app.maria.global.config.properties.CookieProperties;
import com.app.maria.global.config.properties.JwtProperties;
import com.app.maria.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAuthApi.class)
@Import(SecurityConfig.class)
class ActuatorHealthSecurityTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AdminService adminService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean JwtProperties jwtProperties;
    @MockitoBean CookieProperties cookieProperties;

    @Test
    @DisplayName("헬스체크 경로는 보안 필터에서 막지 않는다 (핸들러가 없어 404)")
    void healthIsNotBlockedBySecurity() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("health 외 actuator 경로는 차단된다")
    void otherActuatorPathsAreDenied() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
    }
}
