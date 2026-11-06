package com.app.maria.global.config;

import com.app.maria.global.security.internal.InternalSecretFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 서비스 간 내부 API(/api/internal/**) 전용 체인. 세션·CSRF가 없고 비밀 헤더만 본다. 관리자·고객 체인과 섞지 않는다. */
@Configuration
public class InternalSecurityConfig {

    // 관리자(1)·고객(2)·공개(3) 체인과 겹치지 않는 경로라 순서는 공개 체인(전부 매치)보다 앞이기만 하면 된다.
    @Bean
    @Order(0)
    public SecurityFilterChain internalApiChain(
            HttpSecurity http, @Value("${custom.internal.secret:}") String secret)
            throws Exception {
        http.securityMatcher("/api/internal/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INTERNAL"))
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                (req, res, e) ->
                                                        writeError(res, 401, "UNAUTHORIZED"))
                                        .accessDeniedHandler(
                                                (req, res, e) -> writeError(res, 403, "FORBIDDEN")))
                .addFilterBefore(
                        new InternalSecretFilter(secret),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, int status, String code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\": \"%s\"}".formatted(code));
    }
}
