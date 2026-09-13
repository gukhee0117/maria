package com.app.maria.domain.admin.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.admin.dto.request.AdminRoleUpdateRequestDTO;
import com.app.maria.domain.admin.dto.response.AdminSummaryResponseDTO;
import com.app.maria.domain.admin.exception.AdminNotFoundException;
import com.app.maria.domain.admin.service.AdminService;
import com.app.maria.domain.admin.type.AdminRole;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminManagementApi.class)
@Import(SecurityConfig.class)
class AdminManagementApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AdminService adminService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("ADMIN 권한이면 역할 변경에 성공하고 200을 반환한다")
    @WithMockUser(roles = "ADMIN")
    void updateRoleReturns200WhenCallerIsAdmin() throws Exception {
        mockMvc.perform(patch("/api/admin/admins/{adminId}/role", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminRoleUpdateRequestDTO.builder().role(AdminRole.REVIEWER).build())))
            .andExpect(status().isOk());
        verify(adminService).updateRole(any(), eq(1L), eq(AdminRole.REVIEWER));
    }

    @Test
    @DisplayName("ADMIN이 아니면 403을 반환한다")
    @WithMockUser(roles = "REVIEWER")
    void updateRoleReturns403WhenCallerIsNotAdmin() throws Exception {
        mockMvc.perform(patch("/api/admin/admins/{adminId}/role", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminRoleUpdateRequestDTO.builder().role(AdminRole.REVIEWER).build())))
            .andExpect(status().isForbidden());
        verify(adminService, never()).updateRole(any(), anyLong(), any());
    }

    @Test
    @DisplayName("인증되지 않은 요청이면 401을 반환한다")
    void updateRoleReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(patch("/api/admin/admins/{adminId}/role", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminRoleUpdateRequestDTO.builder().role(AdminRole.REVIEWER).build())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("role이 없으면 400을 반환한다")
    @WithMockUser(roles = "ADMIN")
    void updateRoleReturns400WhenRoleMissing() throws Exception {
        mockMvc.perform(patch("/api/admin/admins/{adminId}/role", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminRoleUpdateRequestDTO.builder().build())))
            .andExpect(status().isBadRequest());
        verify(adminService, never()).updateRole(any(), anyLong(), any());
    }

    @Test
    @DisplayName("대상 관리자가 없으면 404를 반환한다")
    @WithMockUser(roles = "ADMIN")
    void updateRoleReturns404WhenAdminNotFound() throws Exception {
        doThrow(new AdminNotFoundException("대상 관리자가 없습니다."))
            .when(adminService).updateRole(any(), eq(1L), eq(AdminRole.REVIEWER));

        mockMvc.perform(patch("/api/admin/admins/{adminId}/role", 1L)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminRoleUpdateRequestDTO.builder().role(AdminRole.REVIEWER).build())))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADMIN 권한이면 관리자 목록을 200과 함께 반환한다")
    @WithMockUser(roles = "ADMIN")
    void getAllAdminsReturns200WithList() throws Exception {
        when(adminService.getAllAdmins()).thenReturn(List.of(
            AdminSummaryResponseDTO.builder().adminId(1L).loginId("reviewer1")
                .name("이은정").role(AdminRole.REVIEWER).build()));

        mockMvc.perform(get("/api/admin/admins"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("ADMIN이 아니면 관리자 목록 조회 시 403을 반환한다")
    @WithMockUser(roles = "VIEWER")
    void getAllAdminsReturns403WhenNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/admins"))
            .andExpect(status().isForbidden());
        verify(adminService, never()).getAllAdmins();
    }

    @Test
    @DisplayName("인증되지 않으면 관리자 목록 조회 시 401을 반환한다")
    void getAllAdminsReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/admins"))
            .andExpect(status().isUnauthorized());
        verify(adminService, never()).getAllAdmins();
    }
}
