package com.app.maria.domain.customer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.app.maria.domain.customer.service.CustomerSignupEmailSender;
import com.app.maria.domain.customer.service.CustomerSignupVerificationService;
import com.app.maria.domain.customer.service.SignupCodeStore;
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
@Import({
    CustomerSecurityConfig.class,
    CustomerAuthTestBeans.class,
    CustomerSignupVerificationService.class
})
class CustomerAuthApiTest {
    private static final String SIGNUP = "/api/customer/auth/signup";
    private static final String EXPIRES_AT = "2026-10-25T03:30:00Z";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerAuthService customerAuthService;
    @MockitoBean private SignupCodeStore codes;
    @MockitoBean private CustomerSignupEmailSender emailSender;

    private static String body(String loginId, String password, String rrn) {
        return """
                {"loginId":"%s","password":"%s","rrn":"%s","name":"홍길동","birthDate":"1990-01-01","phone":"010-1234-5678","email":"hong@example.com"}
                """
                .formatted(loginId, password, rrn);
    }

    private static String signupJson(String phone, String email) {
        String phoneField = phone == null ? "" : ",\"phone\":\"" + phone + "\"";
        String emailField = email == null ? "" : ",\"email\":\"" + email + "\"";
        return "{\"loginId\":\"hong1234\",\"password\":\"password-1234\","
                + "\"rrn\":\"9001011234567\",\"name\":\"홍길동\",\"birthDate\":\"1990-01-01\""
                + phoneField
                + emailField
                + "}";
    }

    @Test
    @DisplayName("CSRF 토큰 없이 가입하면 403이다")
    void signupWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(
                        post(SIGNUP)
                                .session(verifiedSession())
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
                                        .session(verifiedSession())
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

    private MockHttpSession verifiedSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                CustomerAuthApi.class.getName() + ".pending",
                new CustomerSignupVerificationService.Pending(
                        "hong@example.com",
                        "identity",
                        "token",
                        CustomerAuthTestBeans.NOW.plusSeconds(600),
                        CustomerAuthTestBeans.NOW,
                        true));
        return session;
    }

    @Test
    void seededCustomerLogsInWithoutEmailOrOtpAndGetsThirtyMinuteSession() throws Exception {
        when(customerAuthService.authenticate(any()))
                .thenReturn(new CustomerPrincipal(7L, "hong1234", "홍길동"));
        var result =
                mockMvc.perform(
                                post("/api/customer/auth/login")
                                        .with(csrf())
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                "{\"loginId\":\"hong1234\",\"password\":\"password-1234\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.customerId").value(7))
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getMaxInactiveInterval()).isEqualTo(1800);
        mockMvc.perform(get("/api/customer/auth/me").session(session)).andExpect(status().isOk());
        org.mockito.Mockito.verifyNoInteractions(codes, emailSender);
    }

    @Test
    void signupWithoutEmailProofCannotWriteAnything() throws Exception {
        mockMvc.perform(
                        post(SIGNUP)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_SIGNUP_CODE_INVALID"));
        verify(customerAuthService, never()).signup(any());
    }

    @Test
    void changedEmailCannotUseVerifiedProof() throws Exception {
        mockMvc.perform(
                        post(SIGNUP)
                                .session(verifiedSession())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        body("hong1234", "password-1234", "9001011234567")
                                                .replace("hong@example.com", "other@example.com")))
                .andExpect(status().isBadRequest());
        verify(customerAuthService, never()).signup(any());
    }

    @Test
    void rejectedAnonymousSendDoesNotLeaveAnUnusedSession() throws Exception {
        var result =
                mockMvc.perform(
                                post("/api/customer/auth/signup/email")
                                        .with(csrf())
                                        .contentType(APPLICATION_JSON)
                                        .content("{\"email\":\"hong@example.com\"}"))
                        .andExpect(status().isTooManyRequests())
                        .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        org.mockito.Mockito.verifyNoInteractions(emailSender);
    }

    @Test
    void smtpRunsOutsideSessionLockAndRegistersOnlyAfterSending() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(codes.reserveSend(any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            assertThat(Thread.holdsLock(session)).isFalse();
                            assertThat(
                                            session.getAttribute(
                                                    CustomerAuthApi.class.getName() + ".pending"))
                                    .isNull();
                            return null;
                        })
                .when(emailSender)
                .send(any(), any());
        mockMvc.perform(
                        post("/api/customer/auth/signup/email")
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"hong@example.com\"}"))
                .andExpect(status().isOk());
        assertThat(session.getAttribute(CustomerAuthApi.class.getName() + ".pending")).isNotNull();
        assertThat(session.getAttribute(CustomerAuthApi.class.getName() + ".sending")).isNull();
    }

    @Test
    void sessionInvalidatedDuringSmtpCannotRegisterCodeAndCancelsOnlyThatCode() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(codes.reserveSend(any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            session.invalidate();
                            return null;
                        })
                .when(emailSender)
                .send(any(), any());
        mockMvc.perform(
                        post("/api/customer/auth/signup/email")
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"hong@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_SIGNUP_CODE_INVALID"));
        verify(codes).cancel(any(), any());
    }

    @Test
    void supersededSmtpCompletionCannotOverwriteNewerRequest() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(codes.reserveSend(any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            session.setAttribute(
                                    CustomerAuthApi.class.getName() + ".sending", "newer-token");
                            return null;
                        })
                .when(emailSender)
                .send(any(), any());
        mockMvc.perform(
                        post("/api/customer/auth/signup/email")
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"hong@example.com\"}"))
                .andExpect(status().isBadRequest());
        assertThat(session.getAttribute(CustomerAuthApi.class.getName() + ".pending")).isNull();
        assertThat(session.getAttribute(CustomerAuthApi.class.getName() + ".sending"))
                .isEqualTo("newer-token");
        verify(codes).cancel(any(), any());
    }

    @Test
    void smtpFailureClearsOnlyOwnSendingStateAndDoesNotRegisterCode() throws Exception {
        MockHttpSession session = new MockHttpSession();
        when(codes.reserveSend(any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new AppException(ErrorType.CUSTOMER_SIGNUP_EMAIL_UNAVAILABLE))
                .when(emailSender)
                .send(any(), any());
        mockMvc.perform(
                        post("/api/customer/auth/signup/email")
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"hong@example.com\"}"))
                .andExpect(status().isServiceUnavailable());
        assertThat(session.getAttribute(CustomerAuthApi.class.getName() + ".pending")).isNull();
        assertThat(session.getAttribute(CustomerAuthApi.class.getName() + ".sending")).isNull();
        verify(codes).cancel(any(), any());
    }

    @Test
    void malformedOrMissingEmailIsRejectedAtApiBoundaryBeforePreparingCode() throws Exception {
        for (String json : List.of("{}", "{\"email\":\"\"}", "{\"email\":\"invalid\"}")) {
            mockMvc.perform(
                            post("/api/customer/auth/signup/email")
                                    .with(csrf())
                                    .contentType(APPLICATION_JSON)
                                    .content(json))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(codes, emailSender);
    }

    @Test
    void emailCodeMustBeConfirmedInSameBrowserBeforeSignup() throws Exception {
        when(codes.reserveSend(any(), any(), any())).thenReturn(true);
        var result =
                mockMvc.perform(
                                post("/api/customer/auth/signup/email")
                                        .with(csrf())
                                        .contentType(APPLICATION_JSON)
                                        .content("{\"email\":\"hong@example.com\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/customer/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        when(codes.consume(any(), any(), any())).thenReturn(false, true);
        mockMvc.perform(
                        post("/api/customer/auth/signup/email/verify")
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/customer/auth/signup/email/verify")
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/customer/auth/signup/email/verify")
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verifiedUntil").value("2026-10-25T03:10:00Z"));
        when(customerAuthService.signup(any()))
                .thenReturn(new CustomerPrincipal(7L, "hong1234", "홍길동"));
        mockMvc.perform(
                        post(SIGNUP)
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post(SIGNUP)
                                .session(session)
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isBadRequest());
        verify(customerAuthService, times(1)).signup(any());
    }

    @Test
    void signupCodeSendAndVerifyRequireCsrf() throws Exception {
        mockMvc.perform(
                        post("/api/customer/auth/signup/email/verify")
                                .contentType(APPLICATION_JSON)
                                .content("{\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/customer/auth/signup/email")
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"hong@example.com\"}"))
                .andExpect(status().isForbidden());
    }

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
                                .session(verifiedSession())
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
                Arguments.of("3자 아이디", body("abc", "password-1234", "9001011234567")),
                Arguments.of("특수문자 없는 비밀번호", body("hong1234", "password1234", "9001011234567")),
                Arguments.of("숫자 없는 비밀번호", body("hong1234", "password-abcd", "9001011234567")),
                Arguments.of("영문 없는 비밀번호", body("hong1234", "1234-5678", "9001011234567")),
                Arguments.of("휴대폰 형식 오류", signupJson("123", "hong@example.com")),
                Arguments.of("이메일 형식 오류", signupJson("010-1234-5678", "hong@example")),
                Arguments.of("휴대폰 없음", signupJson(null, "hong@example.com")),
                Arguments.of("이메일 없음", signupJson("010-1234-5678", null)));
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
                                .session(verifiedSession())
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
                                .session(verifiedSession())
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

    @Test
    @DisplayName("가입이 동시 충돌(UNIQUE 위반)로 취소되면 한 번 다시 시도해 성공한다")
    void signupRetriesOnceOnConflict() throws Exception {
        when(customerAuthService.signup(any()))
                .thenThrow(
                        new AppException(
                                ErrorType.CUSTOMER_SIGNUP_FAILED,
                                null,
                                new org.springframework.dao.DataIntegrityViolationException("dup")))
                .thenReturn(new CustomerPrincipal(7L, "hong1234", "홍길동"));

        mockMvc.perform(
                        post(SIGNUP)
                                .session(verifiedSession())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isOk());

        verify(customerAuthService, times(2)).signup(any());
    }

    @Test
    @DisplayName("가입 실패가 동시 충돌 때문이 아니면 다시 시도하지 않는다")
    void signupDoesNotRetryOtherFailures() throws Exception {
        when(customerAuthService.signup(any()))
                .thenThrow(new AppException(ErrorType.CUSTOMER_SIGNUP_FAILED));

        mockMvc.perform(
                        post(SIGNUP)
                                .session(verifiedSession())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isBadRequest());

        verify(customerAuthService, times(1)).signup(any());
    }

    @Test
    @DisplayName("동시 충돌이 두 번 연속이면 두 번째 오류를 그대로 돌려준다(무한 반복 없음)")
    void signupRetriesOnlyOnce() throws Exception {
        when(customerAuthService.signup(any()))
                .thenThrow(
                        new AppException(
                                ErrorType.CUSTOMER_SIGNUP_FAILED,
                                null,
                                new org.springframework.dao.DataIntegrityViolationException(
                                        "dup")));

        mockMvc.perform(
                        post(SIGNUP)
                                .session(verifiedSession())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(body("hong1234", "password-1234", "9001011234567")))
                .andExpect(status().isBadRequest());

        verify(customerAuthService, times(2)).signup(any());
    }

    private void assertBadRequest(String json) throws Exception {
        mockMvc.perform(
                        post(SIGNUP)
                                .session(verifiedSession())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(json))
                .andExpect(status().isBadRequest());

        verify(customerAuthService, never()).signup(any());
    }
}
