package com.app.maria.domain.customer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.service.CustomerAuthService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerAuthApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerAuthApiTest {
    private static final String SIGNUP = "/api/customer/auth/signup";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerAuthService customerAuthService;

    private static String body(String loginId, String password, String rrn) {
        return """
                {"loginId":"%s","password":"%s","rrn":"%s","name":"홍길동","birthDate":"1990-01-01"}
                """
                .formatted(loginId, password, rrn);
    }

    @Test
    @DisplayName("CSRF 토큰 없이 가입하면 403이다")
    void signupWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(
                        post(SIGNUP)
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verify(customerAuthService, never()).signup(any());
    }

    @Test
    @DisplayName("가입하면 로그인되어 같은 세션으로 내 정보를 조회할 수 있다")
    void signupCreatesSessionAndMeWorks() throws Exception {
        when(customerAuthService.signup(any()))
                .thenReturn(new CustomerPrincipal(7L, "hong1234", "홍길동"));

        MvcResult signup =
                mockMvc.perform(
                                post(SIGNUP)
                                        .with(csrf())
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                body("hong1234", "password-1234", "9001011234567")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.loginId").value("hong1234"))
                        .andReturn();

        MockHttpSession session = (MockHttpSession) signup.getRequest().getSession(false);
        mockMvc.perform(get("/api/customer/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(7))
                .andExpect(jsonPath("$.data.loginId").value("hong1234"));
    }

    @Test
    @DisplayName("가입·로그인으로 만든 세션은 30분 동안 유지된다")
    void sessionLastsTwentyMinutes() throws Exception {
        when(customerAuthService.signup(any()))
                .thenReturn(new CustomerPrincipal(7L, "hong1234", "홍길동"));
        when(customerAuthService.authenticate(any()))
                .thenReturn(new CustomerPrincipal(7L, "hong1234", "홍길동"));

        MvcResult signup =
                mockMvc.perform(
                                post(SIGNUP)
                                        .with(csrf())
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                body("hong1234", "password-1234", "9001011234567")))
                        .andReturn();
        MvcResult login =
                mockMvc.perform(
                                post("/api/customer/auth/login")
                                        .with(csrf())
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                "{\"loginId\":\"hong1234\",\"password\":\"password-1234\"}"))
                        .andReturn();

        assertThat(signup.getRequest().getSession(false).getMaxInactiveInterval()).isEqualTo(1800);
        assertThat(login.getRequest().getSession(false).getMaxInactiveInterval()).isEqualTo(1800);
    }

    // 고정 시계(2026-10-25T03:00:00Z) + 30분
    private static final String EXPIRES_AT = "2026-10-25T03:30:00Z";

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private static MockHttpSession thirtyMinuteSession() {
        MockHttpSession session = new MockHttpSession();
        session.setMaxInactiveInterval(1800);
        return session;
    }

    @Test
    @DisplayName("내 정보 조회는 세션 만료 시각을 함께 돌려준다")
    void meReturnsExpiresAt() throws Exception {
        mockMvc.perform(
                        get("/api/customer/auth/me")
                                .with(customerAuth())
                                .session(thirtyMinuteSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").value(EXPIRES_AT));
    }

    @Test
    @DisplayName("연장하면 지금부터 30분 뒤로 만료 시각을 돌려준다")
    void extendReturnsRenewedExpiresAt() throws Exception {
        mockMvc.perform(
                        post("/api/customer/auth/extend")
                                .with(customerAuth())
                                .with(csrf())
                                .session(thirtyMinuteSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").value(EXPIRES_AT));
    }

    @Test
    @DisplayName("로그인하지 않으면 연장할 수 없다")
    void extendRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/customer/auth/extend").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF 토큰 없이는 연장할 수 없다")
    void extendRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/customer/auth/extend").with(customerAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("가입 응답에도 세션 만료 시각이 들어 있다")
    void signupReturnsExpiresAt() throws Exception {
        when(customerAuthService.signup(any()))
                .thenReturn(new CustomerPrincipal(7L, "hong1234", "홍길동"));

        mockMvc.perform(
                        post(SIGNUP)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(jsonPath("$.data.expiresAt").value(EXPIRES_AT));
    }

    static Stream<Arguments> invalidSignupBodies() {
        return Stream.of(
                Arguments.of("하이픈이 섞인 주민번호", body("hong1234", "password-1234", "900101-1234567")),
                Arguments.of("13자리가 아닌 주민번호", body("hong1234", "password-1234", "900101123456")),
                Arguments.of("73자 비밀번호", body("hong1234", "a".repeat(73), "9001011234567")),
                Arguments.of("8자 미만 비밀번호", body("hong1234", "short12", "9001011234567")),
                Arguments.of(
                        "한글 비밀번호(BCrypt 72바이트 제한)",
                        body("hong1234", "가".repeat(30), "9001011234567")),
                Arguments.of("3자 아이디", body("abc", "password-1234", "9001011234567")));
    }

    @ParameterizedTest(name = "{0}은 거절한다")
    @MethodSource("invalidSignupBodies")
    void rejectsInvalidSignupInput(String caseName, String json) throws Exception {
        assertBadRequest(json);
    }

    @Test
    @DisplayName("생년월일 형식이 틀리면 400이다")
    void rejectsMalformedBirthDate() throws Exception {
        mockMvc.perform(
                        post(SIGNUP)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {"loginId":"hong1234","password":"password-1234",\
                                        "rrn":"9001011234567","name":"홍길동","birthDate":"1990-13-01"}
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("가입 실패는 고정 문구로 응답하고 주민번호를 응답에 싣지 않는다")
    void signupFailureIsUniform() throws Exception {
        when(customerAuthService.signup(any()))
                .thenThrow(new AppException(ErrorType.CUSTOMER_SIGNUP_FAILED));

        mockMvc.perform(
                        post(SIGNUP)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_SIGNUP_FAILED"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "9001011234567"))));
    }

    private void assertBadRequest(String json) throws Exception {
        mockMvc.perform(post(SIGNUP).with(csrf()).contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());

        verify(customerAuthService, never()).signup(any());
    }
}
