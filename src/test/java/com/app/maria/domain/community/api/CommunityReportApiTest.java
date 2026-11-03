package com.app.maria.domain.community.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.community.dto.request.ReportRequestDTO;
import com.app.maria.domain.community.service.CommunityReportService;
import com.app.maria.domain.community.type.ReportReason;
import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CommunityReportApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class CommunityReportApiTest {
    private static final String URL = "/api/customer/community/reports";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CommunityReportService reportService;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    @DisplayName("로그인하지 않으면 401, CSRF 없이 신고하면 403이다")
    void authAndCsrf() throws Exception {
        String body = "{\"postId\":11,\"reason\":\"SPAM\"}";
        mockMvc.perform(post(URL).with(csrf()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).with(customerAuth()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("신고자는 로그인한 고객이고 본문의 reporterId·customerId는 무시한다")
    void reportUsesAuthenticatedCustomer() throws Exception {
        mockMvc.perform(
                        post(URL)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        "{\"reporterId\":999,\"customerId\":999,\"postId\":11,\"reason\":\"PRIVACY\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReportRequestDTO> captor = ArgumentCaptor.forClass(ReportRequestDTO.class);
        verify(reportService).report(eq(7L), captor.capture());
        assertThat(captor.getValue().postId()).isEqualTo(11L);
        assertThat(captor.getValue().commentId()).isNull();
        assertThat(captor.getValue().reason()).isEqualTo(ReportReason.PRIVACY);
    }

    @Test
    @DisplayName("사유가 없거나 모르는 값이거나 번호가 0 이하면 400이다")
    void validation() throws Exception {
        for (String body :
                List.of(
                        "{\"postId\":11}",
                        "{\"postId\":11,\"reason\":\"BORED\"}",
                        "{\"postId\":0,\"reason\":\"SPAM\"}",
                        "{\"commentId\":-2,\"reason\":\"SPAM\"}",
                        "{}")) {
            mockMvc.perform(
                            post(URL)
                                    .with(customerAuth())
                                    .with(csrf())
                                    .contentType(APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("중복 신고는 409와 code로 알린다")
    void duplicateMapped() throws Exception {
        doThrow(new AppException(ErrorType.COMMUNITY_REPORT_DUPLICATED))
                .when(reportService)
                .report(eq(7L), any());

        mockMvc.perform(
                        post(URL)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content("{\"commentId\":31,\"reason\":\"ABUSE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_REPORT_DUPLICATED"));
    }
}
