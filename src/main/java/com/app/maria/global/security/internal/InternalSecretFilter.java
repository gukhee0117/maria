package com.app.maria.global.security.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 헤더 X-Internal-Secret이 설정한 비밀과 같을 때만 서비스 간 호출로 인정한다. 비밀을 설정하지 않았으면 모든 요청을 거절한다. */
public class InternalSecretFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Internal-Secret";

    private final byte[] secret;

    public InternalSecretFilter(String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        if (secret.length > 0
                && given != null
                // 시간 차이로 비밀을 추측하지 못하도록 상수 시간 비교
                && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), secret)) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    "securities-generator",
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
        }
        chain.doFilter(request, response);
    }
}
