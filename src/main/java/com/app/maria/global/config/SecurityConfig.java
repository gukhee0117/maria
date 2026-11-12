package com.app.maria.global.config;

import com.app.maria.global.jwt.JwtAuthenticationFilter;
import com.app.maria.global.jwt.JwtTokenProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    // ─── Order(1): 관리자 API — JWT 필수 ──────────────────────────────────────
    // 관리자 체인은 세션을 쓰지 않는다. 기본 세션 고정 방어(세션 ID 교체)가 돌면, 같은 호스트에서 고객이 로그인해 둔 JSESSIONID를 관리자 요청이 바꿔 고객이
    // 로그아웃되므로 NullAuthenticatedSessionStrategy로 끈다.
    @Bean
    @Order(1)
    public SecurityFilterChain adminApiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/admin/**")
                .sessionManagement(
                        s ->
                                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                                        .sessionAuthenticationStrategy(
                                                new NullAuthenticatedSessionStrategy()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(
                                                (req, res, e) ->
                                                        writeJsonError(
                                                                res,
                                                                HttpServletResponse.SC_UNAUTHORIZED,
                                                                "UNAUTHORIZED",
                                                                "로그인이 필요합니다."))
                                        .accessDeniedHandler(
                                                (req, res, e) ->
                                                        writeJsonError(
                                                                res,
                                                                HttpServletResponse.SC_FORBIDDEN,
                                                                "FORBIDDEN",
                                                                "접근 권한이 없습니다.")))
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // Order(2)는 CustomerSecurityConfig의 고객 세션 API가 사용한다.
    // ─── Order(3): 화면·인증 엔드포인트 — 명시 허용 외 전부 차단 ──────────────
    @Bean
    @Order(3)
    public SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        // 인증 엔드포인트
                                        .requestMatchers("/api/auth/**")
                                        .permitAll()
                                        // 컨테이너 헬스체크 (nginx가 /actuator/ 외부 접근을 막는다)
                                        .requestMatchers("/actuator/health")
                                        .permitAll()
                                        // 정적 리소스
                                        .requestMatchers(
                                                "/",
                                                "/favicon.ico",
                                                "/css/**",
                                                "/js/**",
                                                "/images/**")
                                        .permitAll()
                                        // Thymeleaf 화면 라우트
                                        .requestMatchers("/login", "/admin/**")
                                        .permitAll()
                                        // Swagger
                                        .requestMatchers(
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/api-docs/**")
                                        .permitAll()
                                        // 나머지 전부 차단
                                        .anyRequest()
                                        .denyAll());

        return http.build();
    }

    private static void writeJsonError(
            HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write("{\"code\": \"%s\", \"message\": \"%s\"}".formatted(code, message));
    }
}
