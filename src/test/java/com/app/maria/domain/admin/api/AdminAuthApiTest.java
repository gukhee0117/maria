package com.app.maria.domain.admin.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.app.maria.domain.admin.dto.request.AdminLoginRequestDTO;
import com.app.maria.domain.admin.dto.response.AdminLoginResponseDTO;
import com.app.maria.domain.admin.exception.AdminException;
import com.app.maria.domain.admin.exception.AdminNotFoundException;
import com.app.maria.domain.admin.service.AdminService;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.config.properties.CookieProperties;
import com.app.maria.global.config.properties.JwtProperties;
import com.app.maria.global.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAuthApi.class)
@Import(SecurityConfig.class)
class AdminAuthApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AdminService adminService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean JwtProperties jwtProperties;
    @MockitoBean CookieProperties cookieProperties;

    @Test
    @DisplayName("로그인 성공시 HttpOnly 쿠키에 토큰이 담긴다")
    void loginSetsHttpOnlyCookiesOnSuccess() throws Exception {
        when(jwtProperties.getExpirationMinute()).thenReturn(20L);
        when(jwtProperties.getRefreshExpirationDay()).thenReturn(7L);
        when(adminService.login(any())).thenReturn(
            AdminLoginResponseDTO.builder().accessToken("at").refreshToken("rt").build());

        mockMvc.perform(post("/api/auth/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminLoginRequestDTO.builder().loginId("reviewer1").password("pw").build())))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("access_token"))
            .andExpect(cookie().httpOnly("access_token", true))
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().httpOnly("refresh_token", true));
    }

    @Test
    @DisplayName("서비스에서 인증 예외가 발생하면 401을 반환한다")
    void loginReturns401WhenServiceThrowsAdminException() throws Exception {
        when(adminService.login(any())).thenThrow(new AdminException("아이디 또는 비밀번호가 일치하지 않습니다."));

        mockMvc.perform(post("/api/auth/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminLoginRequestDTO.builder().loginId("reviewer1").password("wrong").build())))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("아이디가 없으면 400을 반환한다")
    void loginReturns400WhenLoginIdMissing() throws Exception {
        mockMvc.perform(post("/api/auth/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminLoginRequestDTO.builder().password("pw").build())))
            .andExpect(status().isBadRequest());
        verify(adminService, never()).login(any());
    }

    @Test
    @DisplayName("비밀번호가 없으면 400을 반환한다")
    void loginReturns400WhenPasswordMissing() throws Exception {
        mockMvc.perform(post("/api/auth/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    AdminLoginRequestDTO.builder().loginId("reviewer1").build())))
            .andExpect(status().isBadRequest());
        verify(adminService, never()).login(any());
    }

    @Test
    @DisplayName("유효한 refresh_token 쿠키를 보내면 200을 반환한다")
    void refreshReturns200WhenCookiePresent() throws Exception {
        when(jwtProperties.getExpirationMinute()).thenReturn(20L);
        when(jwtProperties.getRefreshExpirationDay()).thenReturn(7L);
        when(adminService.refresh("valid-rt")).thenReturn(
            AdminLoginResponseDTO.builder().accessToken("new-at").refreshToken("valid-rt").build());

        mockMvc.perform(post("/api/auth/admin/refresh")
                .cookie(new Cookie("refresh_token", "valid-rt")))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("access_token"))
            .andExpect(cookie().httpOnly("access_token", true));
    }

    @Test
    @DisplayName("refresh_token 쿠키가 없으면 401을 반환한다")
    void refreshReturns401WhenCookieMissing() throws Exception {
        mockMvc.perform(post("/api/auth/admin/refresh"))
            .andExpect(status().isUnauthorized());
        verify(adminService, never()).refresh(any());
    }

    @Test
    @DisplayName("서비스에서 토큰 예외가 발생하면 401을 반환한다")
    void refreshReturns401WhenTokenInvalid() throws Exception {
        when(jwtProperties.getExpirationMinute()).thenReturn(20L);
        when(jwtProperties.getRefreshExpirationDay()).thenReturn(7L);
        when(adminService.refresh("broken-token")).thenThrow(new AdminException("유효하지 않은 토큰입니다."));

        mockMvc.perform(post("/api/auth/admin/refresh")
                .cookie(new Cookie("refresh_token", "broken-token")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("토큰의 대상 관리자가 없으면 404를 반환한다")
    void refreshReturns404WhenAdminNotFound() throws Exception {
        when(jwtProperties.getExpirationMinute()).thenReturn(20L);
        when(jwtProperties.getRefreshExpirationDay()).thenReturn(7L);
        when(adminService.refresh("valid-rt")).thenThrow(new AdminNotFoundException("대상 관리자가 없습니다."));

        mockMvc.perform(post("/api/auth/admin/refresh")
                .cookie(new Cookie("refresh_token", "valid-rt")))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("로그아웃 요청은 쿠키를 만료시킨다")
    void logoutExpiresCookies() throws Exception {
        mockMvc.perform(post("/api/auth/admin/logout"))
            .andExpect(status().isOk())
            .andExpect(cookie().maxAge("access_token", 0))
            .andExpect(cookie().maxAge("refresh_token", 0));
    }
}
