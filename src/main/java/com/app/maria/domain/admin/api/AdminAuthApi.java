package com.app.maria.domain.admin.api;

import com.app.maria.domain.admin.dto.request.AdminLoginRequestDTO;
import com.app.maria.domain.admin.dto.response.AdminLoginResponseDTO;
import com.app.maria.domain.admin.exception.AdminException;
import com.app.maria.domain.admin.service.AdminService;
import com.app.maria.global.config.properties.CookieProperties;
import com.app.maria.global.config.properties.JwtProperties;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth/admin")
public class AdminAuthApi {

    private final AdminService adminService;
    private final JwtProperties jwtProperties;
    private final CookieProperties cookieProperties;

    @PostMapping("/login")
    public ResponseEntity<ApiResponseDTO<Void>> login(
            @Valid @RequestBody AdminLoginRequestDTO request) {
        AdminLoginResponseDTO tokens = adminService.login(request);
        return tokenCookieResponse("로그인 성공", tokens);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponseDTO<Void>> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {
        if (refreshToken == null) {
            throw new AdminException("refresh_token 쿠키가 없습니다.");
        }
        AdminLoginResponseDTO tokens = adminService.refresh(refreshToken);
        return tokenCookieResponse("토큰이 재발급되었습니다.", tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponseDTO<Void>> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expireCookie("access_token").toString())
                .header(HttpHeaders.SET_COOKIE, expireCookie("refresh_token").toString())
                .body(ApiResponseDTO.of("로그아웃되었습니다."));
    }

    private ResponseEntity<ApiResponseDTO<Void>> tokenCookieResponse(
            String message, AdminLoginResponseDTO tokens) {
        ResponseCookie accessCookie = buildCookie("access_token", tokens.getAccessToken(),
                (int) (jwtProperties.getExpirationMinute() * 60));
        ResponseCookie refreshCookie = buildCookie("refresh_token", tokens.getRefreshToken(),
                (int) (jwtProperties.getRefreshExpirationDay() * 24 * 60 * 60));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, accessCookie.toString())
                .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
                .body(ApiResponseDTO.of(message));
    }

    private ResponseCookie buildCookie(String name, String value, int maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private ResponseCookie expireCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(cookieProperties.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }
}
