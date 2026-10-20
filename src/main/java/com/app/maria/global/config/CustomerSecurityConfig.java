package com.app.maria.global.config;

import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.SpaCsrfTokenRequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** 관리자 JWT 체인과 독립된 고객 세션·CORS·CSRF 설정. */
@Configuration
public class CustomerSecurityConfig {

    @Bean
    public CookieCsrfTokenRepository customerCsrfTokenRepository(
            @Value("${app.cookie.secure:false}") boolean secure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secure));
        return repository;
    }

    @Bean
    public SecurityContextRepository customerSecurityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    public SessionAuthenticationStrategy customerSessionAuthenticationStrategy(
            CookieCsrfTokenRepository customerCsrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        new CsrfAuthenticationStrategy(customerCsrfTokenRepository)));
    }

    @Bean
    public CorsConfigurationSource customerCorsConfigurationSource(
            @Value("${app.customer.allowed-origins:http://localhost:5173}") List<String> origins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowCredentials(true);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/customer/**", configuration);
        return source;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain customerApiChain(
            HttpSecurity http,
            CookieCsrfTokenRepository customerCsrfTokenRepository,
            @Qualifier("customerSecurityContextRepository") SecurityContextRepository repository,
            @Qualifier("customerCorsConfigurationSource") CorsConfigurationSource cors,
            ObjectMapper objectMapper)
            throws Exception {
        http.securityMatcher("/api/customer/**")
                .cors(config -> config.configurationSource(cors))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(config -> config.securityContextRepository(repository).requireExplicitSave(true))
                .csrf(config -> config
                        .csrfTokenRepository(customerCsrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/customer/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/customer/auth/login").permitAll()
                        .anyRequest().hasRole("CUSTOMER"))
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, 401, "UNAUTHORIZED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, exception) -> {
                            boolean csrf = exception instanceof CsrfException;
                            writeError(response, objectMapper, 403,
                                    csrf ? "CSRF_INVALID" : "FORBIDDEN",
                                    csrf ? "CSRF 토큰을 확인해 주세요." : "접근 권한이 없습니다.");
                        }));
        return http.build();
    }

    private static void writeError(
            HttpServletResponse response, ObjectMapper objectMapper, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponseDTO.error(code, message));
    }
}
