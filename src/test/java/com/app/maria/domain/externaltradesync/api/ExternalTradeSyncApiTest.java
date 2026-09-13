package com.app.maria.domain.externaltradesync.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.externaltradesync.dto.response.ExternalTradeSyncResultDTO;
import com.app.maria.domain.externaltradesync.service.ExternalTradeSyncService;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExternalTradeSyncApi.class)
@Import(SecurityConfig.class)
class ExternalTradeSyncApiTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ExternalTradeSyncService externalTradeSyncService;

    @MockitoBean JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("SETTLEMENT 권한이면 200과 함께 동기화 결과를 반환한다")
    @WithMockUser(roles = "SETTLEMENT")
    void executeSyncRunsSyncAndReturnsResultForSettlementRole() throws Exception {
        when(externalTradeSyncService.syncAll())
                .thenReturn(
                        ExternalTradeSyncResultDTO.builder()
                                .customerCount(10)
                                .failedCustomerCount(0)
                                .newJudgementCount(3)
                                .skippedJudgementCount(7)
                                .build());

        mockMvc.perform(post("/api/admin/external-trade-sync/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("동기화 완료 · 신규 3건"))
                .andExpect(jsonPath("$.data.newJudgementCount").value(3))
                .andExpect(jsonPath("$.data.skippedJudgementCount").value(7));

        verify(externalTradeSyncService).syncAll();
    }

    @Test
    @DisplayName("ADMIN 권한이면 200과 함께 동기화 결과를 반환한다")
    @WithMockUser(roles = "ADMIN")
    void executeSyncRunsSyncAndReturnsResultForAdminRole() throws Exception {
        when(externalTradeSyncService.syncAll())
                .thenReturn(ExternalTradeSyncResultDTO.builder().build());

        mockMvc.perform(post("/api/admin/external-trade-sync/jobs")).andExpect(status().isOk());

        verify(externalTradeSyncService).syncAll();
    }

    @Test
    @DisplayName("VIEWER 권한이면 403을 반환하고 동기화는 실행되지 않는다")
    @WithMockUser(roles = "VIEWER")
    void executeSyncReturns403ForViewerRole() throws Exception {
        mockMvc.perform(post("/api/admin/external-trade-sync/jobs")).andExpect(status().isForbidden());

        verify(externalTradeSyncService, never()).syncAll();
    }
}
