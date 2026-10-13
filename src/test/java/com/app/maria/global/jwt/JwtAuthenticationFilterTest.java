package com.app.maria.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain filterChain;
    @Mock Claims claims;

    JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void stubTokenCookie(String token) {
        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("access_token", token)});
        when(jwtTokenProvider.parseClaims(token)).thenReturn(claims);
    }

    @Test
    @DisplayName("refresh 타입 토큰은 SecurityContextHolder에 인증이 등록되지 않는다")
    void doesNotAuthenticateWhenTokenTypeIsRefresh() throws Exception {
        stubTokenCookie("some-token");
        when(claims.get("type")).thenReturn("refresh");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("type 클레임이 없는 토큰도 SecurityContextHolder에 인증이 등록되지 않는다")
    void doesNotAuthenticateWhenTypeClaimIsAbsent() throws Exception {
        stubTokenCookie("some-token");
        when(claims.get("type")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("access 타입 토큰은 SecurityContextHolder에 role 권한과 함께 인증이 등록된다")
    void authenticatesWithRoleWhenTokenTypeIsAccess() throws Exception {
        stubTokenCookie("some-token");
        when(claims.get("type")).thenReturn("access");
        when(claims.getSubject()).thenReturn("1");
        when(claims.get("role", String.class)).thenReturn("ADMIN");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        verify(filterChain).doFilter(request, response);
    }
}
