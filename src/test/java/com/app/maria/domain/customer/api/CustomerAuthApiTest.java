package com.app.maria.domain.customer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.service.CustomerAuthService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(CustomerAuthApi.class)
@Import(CustomerSecurityConfig.class)
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
    @DisplayName("하이픈이 섞인 주민번호는 거절한다")
    void rejectsHyphenatedRrn() throws Exception {
        assertBadRequest(body("hong1234", "password-1234", "900101-1234567"));
    }

    @Test
    @DisplayName("주민번호가 13자리가 아니면 거절한다")
    void rejectsShortRrn() throws Exception {
        assertBadRequest(body("hong1234", "password-1234", "900101123456"));
    }

    @Test
    @DisplayName("비밀번호가 73자 이상이면 거절한다")
    void rejectsPasswordOver72() throws Exception {
        assertBadRequest(body("hong1234", "a".repeat(73), "9001011234567"));
    }

    @Test
    @DisplayName("비밀번호가 8자 미만이면 거절한다")
    void rejectsShortPassword() throws Exception {
        assertBadRequest(body("hong1234", "short12", "9001011234567"));
    }

    @Test
    @DisplayName("한글 비밀번호는 거절한다(BCrypt는 72바이트까지만 처리)")
    void rejectsMultibytePassword() throws Exception {
        assertBadRequest(body("hong1234", "가".repeat(30), "9001011234567"));
    }

    @Test
    @DisplayName("아이디가 3자면 거절한다")
    void rejectsShortLoginId() throws Exception {
        assertBadRequest(body("abc", "password-1234", "9001011234567"));
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
