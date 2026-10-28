package com.app.maria.domain.investorassessment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.customer.CustomerAuthTestBeans;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentResultResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorAssessmentStatusResponseDTO;
import com.app.maria.domain.investorassessment.dto.response.InvestorQuestionResponseDTO;
import com.app.maria.domain.investorassessment.entity.choice.ExperienceChoice;
import com.app.maria.domain.investorassessment.service.InvestorAssessmentService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(InvestorAssessmentApi.class)
@Import({CustomerSecurityConfig.class, CustomerAuthTestBeans.class})
class InvestorAssessmentApiTest {
    private static final String BASE = "/api/customer/investor-assessment";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 28, 9, 0);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private InvestorAssessmentService service;

    private static RequestPostProcessor customerAuth() {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        new CustomerPrincipal(7L, "hong1234", "홍길동"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    private static final String VALID_BODY =
            """
            {"age":"FROM_30_TO_64","purpose":"GROW_ASSETS","income":"FROM_50M_TO_70M",
             "financialAssetRatio":"FROM_10_TO_30","experience":["DEPOSITS"],
             "knowledge":"FAIR","lossTolerance":"UP_TO_20","investmentPeriod":"WITHIN_3_YEARS",
             "derivativeExperience":"NONE"}
            """;

    @Test
    @DisplayName("로그인하지 않으면 세 API 모두 401이다")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get(BASE + "/questions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(BASE).with(csrf()).contentType(APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CSRF 토큰 없이 제출하면 403이다")
    void submitWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .contentType(APPLICATION_JSON)
                                .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        verify(service, never()).submit(any(), any());
    }

    @Test
    @DisplayName("제출하면 로그인한 고객의 ID로 서비스를 부르고 결과를 돌려준다")
    void submitUsesAuthenticatedCustomer() throws Exception {
        when(service.submit(eq(7L), any()))
                .thenReturn(
                        new InvestorAssessmentResultResponseDTO(
                                33, InvestorType.ACTIVE, AT, AT.plusMonths(24)));

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalScore").value(33))
                .andExpect(jsonPath("$.data.investorType").value("ACTIVE"))
                .andExpect(jsonPath("$.data.expiresAt").value("2028-10-28T09:00:00"));

        verify(service).submit(eq(7L), any());
    }

    @Test
    @DisplayName("요청 본문에 고객 ID를 넣어도 쓰지 않는다")
    void bodyCustomerIdIsIgnored() throws Exception {
        when(service.submit(eq(7L), any()))
                .thenReturn(
                        new InvestorAssessmentResultResponseDTO(
                                33, InvestorType.ACTIVE, AT, AT.plusMonths(24)));
        String withCustomerId = VALID_BODY.replaceFirst("\\{", "{\"customerId\":999,");

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(withCustomerId))
                .andExpect(status().isOk());

        verify(service).submit(eq(7L), any());
    }

    @Test
    @DisplayName("문항을 빠뜨리면 400이고 서비스를 부르지 않는다")
    void missingAnswerIsRejected() throws Exception {
        String missing = VALID_BODY.replace("\"derivativeExperience\":\"NONE\"", "\"x\":1");

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(missing))
                .andExpect(status().isBadRequest());

        verify(service, never()).submit(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 보기 값이면 400이다")
    void unknownChoiceIsRejected() throws Exception {
        String unknown = VALID_BODY.replace("FROM_30_TO_64", "NOT_A_CHOICE");

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(unknown))
                .andExpect(status().isBadRequest());

        verify(service, never()).submit(any(), any());
    }

    @Test
    @DisplayName("투자경험을 비워서 보내면 400이다")
    void emptyExperienceIsRejected() throws Exception {
        String empty = VALID_BODY.replace("[\"DEPOSITS\"]", "[]");

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(empty))
                .andExpect(status().isBadRequest());

        verify(service, never()).submit(any(), any());
    }

    @Test
    @DisplayName("투자경험 배열에 null이 들어 있으면 400이다")
    void nullExperienceElementIsRejected() throws Exception {
        String withNull = VALID_BODY.replace("[\"DEPOSITS\"]", "[null]");

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(withNull))
                .andExpect(status().isBadRequest());

        verify(service, never()).submit(any(), any());
    }

    @Test
    @DisplayName("투자경험을 어떤 순서로 보내도 서비스에는 enum 순서로 전달된다")
    void experienceArrivesSortedByEnumOrder() throws Exception {
        when(service.submit(eq(7L), any()))
                .thenReturn(
                        new InvestorAssessmentResultResponseDTO(
                                33, InvestorType.ACTIVE, AT, AT.plusMonths(24)));
        String shuffled =
                VALID_BODY.replace(
                        "[\"DEPOSITS\"]",
                        "[\"ELB_AND_BOND_FUNDS\",\"DEPOSITS\",\"HIGH_RISK_PRODUCTS\"]");

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(shuffled))
                .andExpect(status().isOk());

        ArgumentCaptor<InvestorAssessmentSubmitRequestDTO> captor =
                ArgumentCaptor.forClass(InvestorAssessmentSubmitRequestDTO.class);
        verify(service).submit(eq(7L), captor.capture());
        assertThat(captor.getValue().getExperience())
                .containsExactly(
                        ExperienceChoice.HIGH_RISK_PRODUCTS,
                        ExperienceChoice.ELB_AND_BOND_FUNDS,
                        ExperienceChoice.DEPOSITS);
    }

    @Test
    @DisplayName("'투자 경험이 없어요'를 다른 보기와 같이 보내면 400이다")
    void noneWithOtherExperienceIsRejected() throws Exception {
        String contradictory = VALID_BODY.replace("[\"DEPOSITS\"]", "[\"NONE\",\"DEPOSITS\"]");

        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(contradictory))
                .andExpect(status().isBadRequest());

        verify(service, never()).submit(any(), any());
    }

    static Stream<Arguments> invalidChoiceBodies() {
        return Stream.of(
                Arguments.of("보기를 숫자로 보냄", "\"age\":\"FROM_30_TO_64\"", "\"age\":2"),
                Arguments.of("보기를 숫자 문자열로 보냄", "\"age\":\"FROM_30_TO_64\"", "\"age\":\"2\""),
                Arguments.of("투자경험 원소를 숫자로 보냄", "[\"DEPOSITS\"]", "[4]"),
                Arguments.of("투자경험 원소를 숫자 문자열로 보냄", "[\"DEPOSITS\"]", "[\"4\"]"),
                Arguments.of("투자경험을 중복해서 보냄", "[\"DEPOSITS\"]", "[\"DEPOSITS\",\"DEPOSITS\"]"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidChoiceBodies")
    @DisplayName("보기 이름이 아닌 값이나 중복 선택은 400이고 서비스를 부르지 않는다")
    void invalidChoicesAreRejected(String name, String from, String to) throws Exception {
        mockMvc.perform(
                        post(BASE)
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(VALID_BODY.replace(from, to)))
                .andExpect(status().isBadRequest());

        verify(service, never()).submit(any(), any());
    }

    @Test
    @DisplayName("문항 조회는 문항과 보기를 돌려준다")
    void questionsReturnsList() throws Exception {
        when(service.questions())
                .thenReturn(
                        List.of(
                                new InvestorQuestionResponseDTO(
                                        "age",
                                        "연령",
                                        "나이 구간",
                                        false,
                                        List.of(
                                                new InvestorQuestionResponseDTO.Choice(
                                                        "UNDER_19", "만 19세 미만")))));

        mockMvc.perform(get(BASE + "/questions").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].answerKey").value("age"))
                .andExpect(jsonPath("$.data[0].multiple").value(false))
                .andExpect(jsonPath("$.data[0].choices[0].value").value("UNDER_19"))
                .andExpect(jsonPath("$.data[0].choices[0].score").doesNotExist());
    }

    @Test
    @DisplayName("내 상태 조회는 로그인한 고객의 상태를 돌려준다")
    void meReturnsStatusOfAuthenticatedCustomer() throws Exception {
        when(service.getMine(7L))
                .thenReturn(
                        InvestorAssessmentStatusResponseDTO.of(
                                InvestorType.ACTIVE, AT, AT.plusMonths(24), false));

        mockMvc.perform(get(BASE + "/me").with(customerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.investorType").value("ACTIVE"))
                .andExpect(jsonPath("$.data.assessed").value(true))
                .andExpect(jsonPath("$.data.expired").value(false));
    }
}
