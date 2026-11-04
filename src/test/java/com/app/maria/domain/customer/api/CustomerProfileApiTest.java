package com.app.maria.domain.customer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.account.type.Status;
import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.dto.response.CustomerProfileResponseDTO;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.customer.service.CustomerProfileService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerProfileApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CustomerProfileApiTest {
    private static final String PROFILE = "/api/customer/profile";
    private static final String PASSWORD = "/api/customer/auth/password";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerProfileService profileService;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private static CustomerProfileResponseDTO profile(
            String email, String nickname, CustomerProfileResponseDTO.AccountSummary account) {
        LocalDateTime assessedAt = LocalDateTime.of(2026, 3, 19, 10, 0);
        return new CustomerProfileResponseDTO(
                "홍길동",
                LocalDate.of(1990, 1, 1),
                "010-1234-5678",
                email,
                nickname,
                InvestorType.NEUTRAL,
                true,
                assessedAt,
                assessedAt.plusMonths(24),
                false,
                50,
                true,
                List.of(
                        new CustomerProfileResponseDTO.AssessmentHistoryItem(
                                assessedAt, 50, InvestorType.NEUTRAL)),
                account);
    }

    @Test
    @DisplayName("로그인하지 않으면 조회·수정·비밀번호 변경은 401이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(PROFILE)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(PROFILE).with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PASSWORD).with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("수정과 비밀번호 변경은 CSRF 토큰이 없으면 403이다")
    void requiresCsrf() throws Exception {
        mockMvc.perform(
                        patch(PROFILE)
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content("{\"email\":\"a@b.co\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post(PASSWORD)
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("조회: 로그인한 고객의 id로 서비스를 부르고 원문 연락처를 돌려준다")
    void getsProfile() throws Exception {
        when(profileService.get(7L))
                .thenReturn(
                        profile(
                                "hong@example.com",
                                "재빠른수달1234",
                                new CustomerProfileResponseDTO.AccountSummary(
                                        "7319000024",
                                        Status.OPENED,
                                        LocalDateTime.of(2026, 3, 30, 10, 0),
                                        LocalDateTime.of(2026, 3, 30, 10, 0))));

        mockMvc.perform(get(PROFILE).with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.birthDate").value("1990-01-01"))
                .andExpect(jsonPath("$.data.phone").value("010-1234-5678"))
                .andExpect(jsonPath("$.data.email").value("hong@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("재빠른수달1234"))
                .andExpect(jsonPath("$.data.investorType").value("NEUTRAL"))
                .andExpect(jsonPath("$.data.totalScore").value(50))
                .andExpect(jsonPath("$.data.investorTypePublic").value(true))
                .andExpect(jsonPath("$.data.assessmentHistory[0].totalScore").value(50))
                .andExpect(jsonPath("$.data.account.accountNo").value("7319000024"))
                .andExpect(jsonPath("$.data.account.status").value("OPENED"));
    }

    @Test
    @DisplayName("수정: 형식이 틀린 휴대폰·이메일은 400이고 서비스를 부르지 않는다")
    void rejectsInvalidContact() throws Exception {
        for (String json : List.of("{\"phone\":\"123\"}", "{\"email\":\"hong@example\"}")) {
            mockMvc.perform(
                            patch(PROFILE)
                                    .with(customerAuth())
                                    .with(csrf())
                                    .contentType(APPLICATION_JSON)
                                    .content(json))
                    .andExpect(status().isBadRequest());
        }
        verify(profileService, never()).update(any(), any());
    }

    @Test
    @DisplayName("수정: 이름·생년월일을 보내도 바꾸지 않는다(DTO에 없는 항목은 무시)")
    void ignoresIdentityFields() throws Exception {
        when(profileService.update(eq(7L), any()))
                .thenReturn(profile("new@example.com", null, null));

        mockMvc.perform(
                        patch(PROFILE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"다른이름\",\"birthDate\":\"2000-01-01\","
                                                + "\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("홍길동"));
    }

    @Test
    @DisplayName("비밀번호 변경: 새 비밀번호가 규칙(영문·숫자·특수문자 포함 8자 이상)에 안 맞으면 400이다")
    void rejectsWeakNewPassword() throws Exception {
        for (String weak : List.of("password1234", "short1!", "비밀번호비밀번호1!")) {
            mockMvc.perform(
                            post(PASSWORD)
                                    .with(customerAuth())
                                    .with(csrf())
                                    .contentType(APPLICATION_JSON)
                                    .content(
                                            "{\"currentPassword\":\"password-1234\","
                                                    + "\"newPassword\":\""
                                                    + weak
                                                    + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(profileService, never()).changePassword(any(), any());
    }

    @Test
    @DisplayName("비밀번호 변경: 현재 비밀번호가 틀리면 401이 아니라 400으로 응답한다(화면이 401을 세션 만료로 처리)")
    void wrongCurrentPasswordIs400() throws Exception {
        org.mockito.Mockito.doThrow(new AppException(ErrorType.CUSTOMER_PASSWORD_MISMATCH))
                .when(profileService)
                .changePassword(eq(7L), any());

        mockMvc.perform(
                        post(PASSWORD)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"currentPassword\":\"wrong-pass-1\","
                                                + "\"newPassword\":\"new-pass-5678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_PASSWORD_MISMATCH"));
    }

    @Test
    @DisplayName("비밀번호 변경: 성공하면 200이다")
    void changesPassword() throws Exception {
        mockMvc.perform(
                        post(PASSWORD)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"currentPassword\":\"password-1234\","
                                                + "\"newPassword\":\"new-pass-5678\"}"))
                .andExpect(status().isOk());
        verify(profileService).changePassword(eq(7L), any());
    }
}
