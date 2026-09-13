package com.app.maria.domain.settlement.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.settlement.dto.KrwExchangeDTO;
import com.app.maria.domain.settlement.dto.SettlementBatchDTO;
import com.app.maria.domain.settlement.dto.SettlementItemDTO;
import com.app.maria.domain.settlement.dto.SettlementJoinDTO;
import com.app.maria.domain.settlement.exception.*;
import com.app.maria.domain.settlement.service.SettlementService;
import com.app.maria.domain.settlement.type.BatchStatus;
import com.app.maria.domain.settlement.type.SettlementStatus;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettlementApi.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class SettlementApiTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SettlementService settlementService;

    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    @Test
    void executeSettlementBatchReturnsAcceptedBatch() throws Exception {
        SettlementBatchDTO batch = batch();
        when(settlementService.executeSettlementBatchByAdmin()).thenReturn(batch);

        mockMvc.perform(post("/api/admin/settlement/jobs"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("확정산 배치 실행 요청 완료"))
                .andExpect(jsonPath("$.data.batchId").value(1L))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        verify(settlementService).executeSettlementBatchByAdmin();
    }

    @Test
    void executeSettlementBatchReturnsExistingCompletedBatch() throws Exception {
        SettlementBatchDTO completedBatch = batch();
        completedBatch.setStatus(BatchStatus.COMPLETED);
        when(settlementService.executeSettlementBatchByAdmin()).thenReturn(completedBatch);

        mockMvc.perform(post("/api/admin/settlement/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotExecuteSettlementBatch() throws Exception {
        mockMvc.perform(post("/api/admin/settlement/jobs")).andExpect(status().isForbidden());

        verify(settlementService, never()).executeSettlementBatchByAdmin();
    }

    @Test
    void retryEndpointsDelegateToService() throws Exception {
        SettlementItemDTO item =
                SettlementItemDTO.builder().itemId(10L).batchId(1L).exchangeId(100L).build();
        SettlementBatchDTO batch = batch();
        when(settlementService.retryFailedSettlementItem(1L, 10L)).thenReturn(item);
        when(settlementService.retryFailedSettlementBatch(1L)).thenReturn(batch);

        mockMvc.perform(post("/api/admin/settlement/batches/{batchId}/items/{itemId}/retry", 1L, 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemId").value(10L));
        mockMvc.perform(post("/api/admin/settlement/batches/{batchId}/retry", 1L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.batchId").value(1L));

        verify(settlementService).retryFailedSettlementItem(1L, 10L);
        verify(settlementService).retryFailedSettlementBatch(1L);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotRetrySettlementItem() throws Exception {
        mockMvc.perform(post("/api/admin/settlement/batches/{batchId}/items/{itemId}/retry", 1L, 10L))
                .andExpect(status().isForbidden());

        verify(settlementService, never()).retryFailedSettlementItem(1L, 10L);
    }

    @Test
    @WithMockUser(roles = "REVIEWER")
    void reviewerCannotRetrySettlementBatch() throws Exception {
        mockMvc.perform(post("/api/admin/settlement/batches/{batchId}/retry", 1L))
                .andExpect(status().isForbidden());

        verify(settlementService, never()).retryFailedSettlementBatch(1L);
    }

    @Test
    void getSettlementBatchEndpointsReturnBatch() throws Exception {
        SettlementBatchDTO batch = batch();
        when(settlementService.getSettlementBatches()).thenReturn(List.of(batch));
        when(settlementService.getSettlementBatch(1L)).thenReturn(batch);
        when(settlementService.getSettlementBatchByRunId("run-1")).thenReturn(batch);

        mockMvc.perform(get("/api/admin/settlement/batches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].batchId").value(1L));
        mockMvc.perform(get("/api/admin/settlement/batches/{batchId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"));
        mockMvc.perform(get("/api/admin/settlement/batches/run/{runId}", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchId").value(1L));
    }

    @Test
    void getSettlementBatchDetailEndpointsReturnRequestedItems() throws Exception {
        SettlementJoinDTO detail =
                SettlementJoinDTO.builder().itemId(10L).batchId(1L).exchangeId(100L).build();
        when(settlementService.getSettlementBatchDetail(1L)).thenReturn(List.of(detail));
        when(settlementService.getSettlementBatchFailDetail(1L)).thenReturn(List.of(detail));

        mockMvc.perform(get("/api/admin/settlement/batches/detail/{batchId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].itemId").value(10L));
        mockMvc.perform(get("/api/admin/settlement/batches/detail/fail/{batchId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].itemId").value(10L));

        verify(settlementService).getSettlementBatchDetail(1L);
        verify(settlementService).getSettlementBatchFailDetail(1L);
    }

    @Test
    void getSettlementBatchByRunIdRejectsBlankRunId() throws Exception {
        mockMvc.perform(get("/api/admin/settlement/batches/run/{runId}", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("runId는 필수입니다."));

        verify(settlementService, never()).getSettlementBatchByRunId(" ");
    }

    @Test
    void getSettlementItemEndpointsUseBatchAndCursor() throws Exception {
        SettlementItemDTO item =
                SettlementItemDTO.builder().itemId(10L).batchId(1L).exchangeId(100L).build();
        SettlementJoinDTO detail =
                SettlementJoinDTO.builder().itemId(10L).batchId(1L).exchangeId(100L).build();
        when(settlementService.getPendingSettlementItems(1L, 0L)).thenReturn(List.of(item));
        when(settlementService.getSettlementItem(1L, 10L)).thenReturn(detail);

        mockMvc.perform(get("/api/admin/settlement/batches/{batchId}/items/pending", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].itemId").value(10L));
        mockMvc.perform(get("/api/admin/settlement/batches/{batchId}/items/{itemId}", 1L, 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exchangeId").value(100L));

        verify(settlementService).getPendingSettlementItems(1L, 0L);
        verify(settlementService).getSettlementItem(1L, 10L);
    }

    @Test
    void getKrwExchangeReturnsExchange() throws Exception {
        KrwExchangeDTO exchange =
                KrwExchangeDTO.builder()
                        .exchangeId(100L)
                        .accountId(1L)
                        .settlementStatus(SettlementStatus.PROVISIONAL)
                        .build();
        when(settlementService.getKrwExchange(100L)).thenReturn(exchange);

        mockMvc.perform(get("/api/admin/settlement/exchanges/{exchangeId}", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exchangeId").value(100L))
                .andExpect(jsonPath("$.data.settlementStatus").value("PROVISIONAL"));

        verify(settlementService).getKrwExchange(100L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void getSettlementBatchRejectsNonPositiveBatchId(long batchId) throws Exception {
        mockMvc.perform(get("/api/admin/settlement/batches/{batchId}", batchId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.status").doesNotExist());

        verify(settlementService, never()).getSettlementBatch(batchId);
    }

    @Test
    void getPendingSettlementItemsRejectsNegativeCursor() throws Exception {
        mockMvc.perform(
                        get("/api/admin/settlement/batches/{batchId}/items/pending", 1L)
                                .queryParam("lastItemId", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.status").doesNotExist());

        verify(settlementService, never()).getPendingSettlementItems(1L, -1L);
    }

    @Test
    void invalidSettlementExceptionReturnsBadRequest() throws Exception {
        when(settlementService.getSettlementBatch(1L))
                .thenThrow(new InvalidSettlementException("batchId - 요청 값 오류"));

        mockMvc.perform(get("/api/admin/settlement/batches/{batchId}", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("batchId - 요청 값 오류"));
    }

    @Test
    void settlementBatchNotFoundExceptionReturnsNotFound() throws Exception {
        when(settlementService.getSettlementBatch(999L))
                .thenThrow(new SettlementBatchNotFoundException("batch id로 배치 조회 실패"));

        mockMvc.perform(get("/api/admin/settlement/batches/{batchId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("batch id로 배치 조회 실패"));
    }

    @Test
    void settlementItemNotFoundExceptionReturnsNotFound() throws Exception {
        when(settlementService.getSettlementItem(1L, 999L))
                .thenThrow(new SettlementItemNotFoundException("item detail 조회 실패"));

        mockMvc.perform(get("/api/admin/settlement/batches/{batchId}/items/{itemId}", 1L, 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("item detail 조회 실패"));
    }

    @Test
    void krwExchangeNotFoundExceptionReturnsNotFound() throws Exception {
        when(settlementService.getKrwExchange(999L))
                .thenThrow(new KrwExchangeNotFoundException("환전 조회 실패"));

        mockMvc.perform(get("/api/admin/settlement/exchanges/{exchangeId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("환전 조회 실패"));
    }

    @Test
    void settlementCalculationExceptionReturnsBadRequest() throws Exception {
        when(settlementService.executeSettlementBatchByAdmin())
                .thenThrow(new SettlementCalculationException("확정산 금액 계산 실패"));

        mockMvc.perform(post("/api/admin/settlement/jobs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("확정산 금액 계산 실패"));
    }

    @Test
    void settlementStateConflictExceptionReturnsConflict() throws Exception {
        when(settlementService.executeSettlementBatchByAdmin())
                .thenThrow(new SettlementStateConflictException("확정산 Batch가 이미 실행 중입니다."));

        mockMvc.perform(post("/api/admin/settlement/jobs"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("확정산 Batch가 이미 실행 중입니다."));
    }

    private SettlementBatchDTO batch() {
        return SettlementBatchDTO.builder()
                .batchId(1L)
                .status(BatchStatus.RUNNING)
                .runId("run-1")
                .build();
    }
}
