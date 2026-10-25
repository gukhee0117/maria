package com.app.maria.domain.customer.api;

import com.app.maria.domain.customer.dto.request.CustomerLoginRequestDTO;
import com.app.maria.domain.customer.dto.request.CustomerSignupRequestDTO;
import com.app.maria.domain.customer.dto.response.CustomerMeResponseDTO;
import com.app.maria.domain.customer.service.CustomerAuthService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
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
    // 유휴 20분 뒤 세션 만료. 서버 설정 파일이 추적되지 않으므로 코드에서 지정한다.
    private static final int SESSION_TIMEOUT_SECONDS = 20 * 60;

    private final CustomerAuthService customerAuthService;
    private final SecurityContextRepository contextRepository;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final CookieCsrfTokenRepository csrfRepository;
    private final Clock clock;

    public CustomerAuthApi(
            CustomerAuthService customerAuthService,
            @Qualifier("customerSecurityContextRepository")
                    SecurityContextRepository contextRepository,
            @Qualifier("customerSessionAuthenticationStrategy")
                    SessionAuthenticationStrategy sessionStrategy,
            CookieCsrfTokenRepository customerCsrfTokenRepository,
            Clock clock) {
        this.customerAuthService = customerAuthService;
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
        this.csrfRepository = customerCsrfTokenRepository;
        this.clock = clock;
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
        establishSession(principal, request, response);
        return ApiResponseDTO.of(
                "로그인 성공", new CustomerMeResponseDTO(principal, expiresAt(request)));
    }

    @PostMapping("/auth/signup")
    @Operation(summary = "고객 회원가입 + 자동 로그인 (CSRF 필수)")
    public ApiResponseDTO<CustomerMeResponseDTO> signup(
            @Valid @RequestBody CustomerSignupRequestDTO body,
            HttpServletRequest request,
            HttpServletResponse response) {
        CustomerPrincipal principal = customerAuthService.signup(body);
        establishSession(principal, request, response);
        return ApiResponseDTO.of("가입 성공", new CustomerMeResponseDTO(principal, expiresAt(request)));
    }

    // 이 요청이 세션을 접근한 시점부터 유휴 시간이 다시 시작되므로 만료 시각 = 지금 + 유휴 허용 시간.
    private Instant expiresAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        int seconds = session == null ? SESSION_TIMEOUT_SECONDS : session.getMaxInactiveInterval();
        return Instant.now(clock).plusSeconds(seconds);
    }

    // 로그인·가입 공통: 세션 ID를 교체하고 인증 정보를 세션에 저장한다.
    private void establishSession(
            CustomerPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setMaxInactiveInterval(SESSION_TIMEOUT_SECONDS);
    }

    @GetMapping("/auth/me")
    @Operation(summary = "현재 고객 세션 확인 (만료 시각 포함)")
    public ApiResponseDTO<CustomerMeResponseDTO> me(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            HttpServletRequest request) {
        return ApiResponseDTO.of(
                "고객 정보 조회 성공", new CustomerMeResponseDTO(principal, expiresAt(request)));
    }

    @PostMapping("/auth/extend")
    @Operation(summary = "세션 연장 (인증·CSRF 필수). 이 요청이 세션을 접근하는 순간 유휴 시간이 다시 20분이 된다")
    public ApiResponseDTO<CustomerMeResponseDTO> extend(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            HttpServletRequest request) {
        request.getSession(false); // 세션을 접근해 컨테이너의 유휴 시간을 갱신한다.
        return ApiResponseDTO.of(
                "세션을 연장했습니다.", new CustomerMeResponseDTO(principal, expiresAt(request)));
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
