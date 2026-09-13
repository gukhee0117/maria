package com.app.maria.domain.admin.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.admin.dto.response.AdminMeResponseDTO;
import com.app.maria.domain.admin.service.AdminService;
import com.app.maria.domain.admin.type.AdminRole;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminMeApi.class)
@Import(SecurityConfig.class)
class AdminMeApiTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AdminService adminService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("로그인한 관리자는 본인 정보를 조회할 수 있다")
    @WithMockUser(username = "5", roles = "REVIEWER")
    void getMeReturns200WithAdminInfo() throws Exception {
        when(adminService.getMe(any()))
            .thenReturn(new AdminMeResponseDTO(5L, "천유진", AdminRole.REVIEWER));

        mockMvc.perform(get("/api/admin/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("천유진"))
            .andExpect(jsonPath("$.data.role").value("REVIEWER"));
    }

    @Test
    @DisplayName("인증되지 않은 요청이면 401을 반환한다")
    void getMeReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/me"))
            .andExpect(status().isUnauthorized());
    }
}
