package com.app.maria.domain.customer.api;

import com.app.maria.domain.customer.dto.request.CustomerLoginRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerMeResponseDTO;
import com.app.maria.domain.customer.service.CustomerAuthService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer")
@SecurityRequirements
public class CustomerAuthApi {
    private final CustomerAuthService customerAuthService;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final CookieCsrfTokenRepository csrfRepository;

    public CustomerAuthApi(
            CustomerAuthService customerAuthService,
            @Qualifier("customerSecurityContextRepository")
                    SecurityContextRepository contextRepository,
            @Qualifier("customerSessionAuthenticationStrategy")
                    SessionAuthenticationStrategy sessionStrategy,
            CookieCsrfTokenRepository customerCsrfTokenRepository) {
        this.customerAuthService = customerAuthService;
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
        this.csrfRepository = customerCsrfTokenRepository;
    }

    @GetMapping("/csrf")
    @Operation(summary = "고객 CSRF 쿠키 초기화 (로그인 전·로그인/로그아웃 성공 후 호출)")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/auth/login")
    @Operation(summary = "고객 LOCAL 로그인 (CSRF 필수, JWT 대신 세션 사용)")
    public ApiResponseDTO<CustomerMeResponseDTO> login(
            @Valid @RequestBody CustomerLoginRequestDTO body,
            HttpServletRequest request,
            HttpServletResponse response) {
        CustomerPrincipal principal = customerAuthService.authenticate(body);
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return ApiResponseDTO.of("로그인 성공", new CustomerMeResponseDTO(principal));
    }

    @GetMapping("/auth/me")
    @Operation(summary = "현재 고객 세션 확인")
    public ApiResponseDTO<CustomerMeResponseDTO> me(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ApiResponseDTO.of("고객 정보 조회 성공", new CustomerMeResponseDTO(principal));
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "고객 세션 로그아웃 (인증·CSRF 필수)")
    public ApiResponseDTO<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        new CsrfLogoutHandler(csrfRepository).logout(request, response, authentication);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
        return ApiResponseDTO.of("로그아웃되었습니다.");
    }
}
