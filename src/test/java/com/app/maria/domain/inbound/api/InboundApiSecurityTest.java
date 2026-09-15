package com.app.maria.domain.inbound.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.inbound.dto.response.InboundResponseDTO;
import com.app.maria.domain.inbound.service.InboundService;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InboundApi.class)
@Import(SecurityConfig.class)
class InboundApiSecurityTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean InboundService inboundService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    private static final String REQUEST_BODY =
            """
            {
              "accountId": 1,
              "foreignProductId": 1,
              "requestedQty": 80
            }
            """;

    @Test
    @DisplayName("REVIEWER 권한이면 200과 함께 입고 처리 결과를 반환한다")
    @WithMockUser(roles = "REVIEWER")
    void processInboundReturns200ForReviewerRole() throws Exception {
        when(inboundService.processInbound(org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        InboundResponseDTO.builder()
                                .inboundId(10L)
                                .requestedQty(BigDecimal.valueOf(80))
                                .approvedQty(BigDecimal.valueOf(80))
                                .build());

        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_BODY))
                .andExpect(status().isOk());

        verify(inboundService).processInbound(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("ADMIN 권한이면 403을 반환하고 입고 처리는 실행되지 않는다")
    @WithMockUser(roles = "ADMIN")
    void processInboundReturns403ForAdminRole() throws Exception {
        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        verify(inboundService, never()).processInbound(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("SETTLEMENT 권한이면 403을 반환하고 입고 처리는 실행되지 않는다")
    @WithMockUser(roles = "SETTLEMENT")
    void processInboundReturns403ForSettlementRole() throws Exception {
        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        verify(inboundService, never()).processInbound(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("VIEWER 권한이면 403을 반환하고 입고 처리는 실행되지 않는다")
    @WithMockUser(roles = "VIEWER")
    void processInboundReturns403ForViewerRole() throws Exception {
        mockMvc.perform(
                        post("/api/admin/inbounds")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_BODY))
                .andExpect(status().isForbidden());

        verify(inboundService, never()).processInbound(org.mockito.ArgumentMatchers.any());
    }
}
