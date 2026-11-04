package com.app.maria.domain.inbound.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.foreignproduct.dto.ForeignProductDTO;
import com.app.maria.domain.inbound.dto.InboundDTO;
import com.app.maria.domain.inbound.dto.request.CustomerInboundSubmitRequestDTO;
import com.app.maria.domain.inbound.dto.response.CustomerInboundSubmitResponseDTO;
import com.app.maria.domain.inbound.service.CustomerInboundQueryService;
import com.app.maria.domain.inbound.service.CustomerInboundService;
import com.app.maria.global.config.CustomerSecurityConfig;
import com.app.maria.global.security.customer.CustomerPrincipal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(CustomerInboundApi.class)
@Import(CustomerSecurityConfig.class)
class CustomerInboundApiTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CustomerInboundService customerInboundService;
    @MockitoBean private CustomerInboundQueryService customerInboundQueryService;

    private static final CustomerPrincipal PRINCIPAL =
            new CustomerPrincipal(1L, "customer@test.com", "테스트고객");

    private static RequestPostProcessor customerAuth() {
        Authentication auth =
                new UsernamePasswordAuthenticationToken(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        return authentication(auth);
    }

    private static CustomerInboundSubmitResponseDTO stubResponse() {
        InboundDTO inbound =
                InboundDTO.builder()
                        .inboundId(1L)
                        .requestedQty(new BigDecimal("100.5000"))
                        .approvedQty(new BigDecimal("100.5000"))
                        .processedAt(LocalDateTime.of(2026, 11, 3, 10, 0))
                        .build();
        ForeignProductDTO product =
                ForeignProductDTO.builder().foreignProductId(1L).ticker("AAPL").name("애플").build();
        return new CustomerInboundSubmitResponseDTO(inbound, product, List.of());
    }

    // ── requestedQty JSON 타입 coercion ──────────────────────────────────────

    /**
     * Jackson이 JSON 문자열 "100.5000"을 BigDecimal requestedQty에 역직렬화할 수 있는지 확인. 프론트엔드가 requestedQty를
     * string으로 전송하므로 400이 반환되면 안 됨.
     */
    @Test
    void submit_requestedQtyAsString_isAccepted() throws Exception {
        when(customerInboundService.submit(eq(1L), any(CustomerInboundSubmitRequestDTO.class)))
                .thenReturn(stubResponse());

        mockMvc.perform(
                        post("/api/customer/inbounds")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "foreignProductId": 1,
                                          "sourceGeneralAccountId": 1,
                                          "requestedQty": "100.5000"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inboundId").value(1))
                .andExpect(jsonPath("$.data.ticker").value("AAPL"));

        verify(customerInboundService).submit(eq(1L), any(CustomerInboundSubmitRequestDTO.class));
    }

    @Test
    void submit_requestedQtyAsNumber_isAccepted() throws Exception {
        when(customerInboundService.submit(eq(1L), any(CustomerInboundSubmitRequestDTO.class)))
                .thenReturn(stubResponse());

        mockMvc.perform(
                        post("/api/customer/inbounds")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "foreignProductId": 1,
                                          "sourceGeneralAccountId": 1,
                                          "requestedQty": 100.5
                                        }
                                        """))
                .andExpect(status().isOk());
    }

    @Test
    void submit_requestedQtyMissing_returnsBadRequest() throws Exception {
        mockMvc.perform(
                        post("/api/customer/inbounds")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "foreignProductId": 1,
                                          "sourceGeneralAccountId": 1
                                        }
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_requestedQtyNegative_returnsBadRequest() throws Exception {
        mockMvc.perform(
                        post("/api/customer/inbounds")
                                .with(customerAuth())
                                .with(csrf())
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "foreignProductId": 1,
                                          "sourceGeneralAccountId": 1,
                                          "requestedQty": "-1"
                                        }
                                        """))
                .andExpect(status().isBadRequest());
    }
}
