package com.app.maria.domain.tax.api;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationSaveResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotBatchResultResponseDTO;
import com.app.maria.domain.tax.dto.response.TaxSnapshotResponseDTO;
import com.app.maria.domain.tax.exception.TaxCalculationAlreadyExistsException;
import com.app.maria.domain.tax.exception.TaxRuleNotFoundException;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.domain.tax.type.TaxBasisType;
import com.app.maria.global.config.SecurityConfig;
import com.app.maria.global.jwt.JwtTokenProvider;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaxApi.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "ADMIN")
class TaxApiTest {

    private static final Long ACCOUNT_ID = 1L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TaxCalculationService taxCalculationService;

    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    private TaxCalculationPreviewResponseDTO preview() {
        return TaxCalculationPreviewResponseDTO.of(
                ACCOUNT_ID,
                TaxCalculationResultDTO.builder()
                        .originalGainAmount(new BigDecimal("32000000"))
                        .weightedGain(new BigDecimal("27800000"))
                        .weightedSell(new BigDecimal("43000000"))
                        .weightedExternalAmount(new BigDecimal("11000000.00"))
                        .adjustRatio(new BigDecimal("0.7442"))
                        .finalDeduction(new BigDecimal("20688760.00"))
                        .finalTax(new BigDecimal("1938472.80"))
                        .build(),
                null,
                null,
                null);
    }

    private TaxCalculationSaveResponseDTO saved() {
        return TaxCalculationSaveResponseDTO.builder()
                .calcId(10L)
                .accountId(ACCOUNT_ID)
                .basisType(TaxBasisType.FINAL_REPORT)
                .calculatedAt(LocalDateTime.of(2027, 5, 1, 9, 0))
                .weightedSell(new BigDecimal("43000000.00"))
                .originalGainAmount(new BigDecimal("32000000.00"))
                .weightedGain(new BigDecimal("27800000.00"))
                .weightedExternalAmount(new BigDecimal("11000000.00"))
                .adjustRatio(new BigDecimal("0.7442"))
                .finalDeduction(new BigDecimal("20688760.00"))
                .finalTax(new BigDecimal("1938472.80"))
                .build();
    }

    @ParameterizedTest(name = "{0}은 미리보기를 볼 수 있다")
    @ValueSource(strings = {"ADMIN", "SETTLEMENT", "REVIEWER", "VIEWER"})
    @DisplayName("미리보기는 모든 역할이 조회할 수 있다")
    void 미리보기_전역할_허용(String role) throws Exception {
        when(taxCalculationService.taxCalculate(ACCOUNT_ID)).thenReturn(preview());

        mockMvc.perform(
                        get("/api/admin/tax/preview/{accountId}", ACCOUNT_ID)
                                .with(user("tester").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("세금계산 성공"))
                .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID))
                .andExpect(jsonPath("$.data.taxCalculationResultDTO.finalTax").value(1938472.80));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("미인증이면 미리보기도 막힌다")
    void 미리보기_미인증() throws Exception {
        mockMvc.perform(get("/api/admin/tax/preview/{accountId}", ACCOUNT_ID))
                .andExpect(status().isUnauthorized());

        verify(taxCalculationService, never()).taxCalculate(anyLong());
    }

    @Test
    @DisplayName("확정 저장은 계산 결과를 응답으로 돌려준다")
    void 확정저장_정상() throws Exception {
        when(taxCalculationService.calculateAndSave(ACCOUNT_ID)).thenReturn(saved());

        mockMvc.perform(post("/api/admin/tax/calculations/{accountId}", ACCOUNT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("세액 확정 저장 성공"))
                .andExpect(jsonPath("$.data.calcId").value(10L))
                .andExpect(jsonPath("$.data.basisType").value("FINAL_REPORT"))
                .andExpect(jsonPath("$.data.finalTax").value(1938472.80));

        verify(taxCalculationService).calculateAndSave(ACCOUNT_ID);
    }

    @ParameterizedTest(name = "{0}은 확정 저장을 할 수 있다")
    @ValueSource(strings = {"ADMIN", "SETTLEMENT"})
    @DisplayName("정산·관리자만 확정 저장할 수 있다")
    void 확정저장_허용역할(String role) throws Exception {
        when(taxCalculationService.calculateAndSave(ACCOUNT_ID)).thenReturn(saved());

        mockMvc.perform(
                        post("/api/admin/tax/calculations/{accountId}", ACCOUNT_ID)
                                .with(user("tester").roles(role)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}은 확정 저장을 할 수 없다")
    @ValueSource(strings = {"REVIEWER", "VIEWER"})
    @DisplayName("심사·조회 역할은 확정 저장이 막힌다")
    void 확정저장_차단역할(String role) throws Exception {
        mockMvc.perform(
                        post("/api/admin/tax/calculations/{accountId}", ACCOUNT_ID)
                                .with(user("tester").roles(role)))
                .andExpect(status().isForbidden());

        verify(taxCalculationService, never()).calculateAndSave(anyLong());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("미인증이면 확정 저장이 막힌다")
    void 확정저장_미인증() throws Exception {
        mockMvc.perform(post("/api/admin/tax/calculations/{accountId}", ACCOUNT_ID))
                .andExpect(status().isUnauthorized());

        verify(taxCalculationService, never()).calculateAndSave(anyLong());
    }

    @Test
    @DisplayName("이미 저장된 계좌면 409로 응답한다")
    void 확정저장_중복() throws Exception {
        when(taxCalculationService.calculateAndSave(ACCOUNT_ID))
                .thenThrow(new TaxCalculationAlreadyExistsException("이미 확정신고된 계좌입니다."));

        mockMvc.perform(post("/api/admin/tax/calculations/{accountId}", ACCOUNT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 확정신고된 계좌입니다."));
    }

    @Test
    @DisplayName("세금 규칙이 없으면 404로 응답한다")
    void 규칙없음() throws Exception {
        when(taxCalculationService.taxCalculate(ACCOUNT_ID))
                .thenThrow(new TaxRuleNotFoundException("TAX_RATE 규칙을 찾지 못했습니다."));

        mockMvc.perform(get("/api/admin/tax/preview/{accountId}", ACCOUNT_ID))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "accountId={0}이면 400")
    @ValueSource(longs = {0L, -1L})
    @DisplayName("accountId가 양수가 아니면 요청 단계에서 거절한다")
    void accountId_양수검증(long accountId) throws Exception {
        mockMvc.perform(get("/api/admin/tax/preview/{accountId}", accountId))
                .andExpect(status().isBadRequest());

        verify(taxCalculationService, never()).taxCalculate(anyLong());
    }

    @Test
    @DisplayName("스냅샷 목록을 계좌 id 여러 개로 한 번에 조회한다")
    void 스냅샷_목록조회() throws Exception {
        when(taxCalculationService.findSnapshots(List.of(1L, 2L)))
                .thenReturn(
                        List.of(
                                TaxSnapshotResponseDTO.builder()
                                        .accountId(1L)
                                        .finalTax(new BigDecimal("100000"))
                                        .build(),
                                TaxSnapshotResponseDTO.builder()
                                        .accountId(2L)
                                        .finalTax(new BigDecimal("0"))
                                        .build()));

        mockMvc.perform(get("/api/admin/tax/snapshots").param("accountIds", "1,2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("세액 스냅샷 조회 성공"))
                .andExpect(jsonPath("$.data[0].accountId").value(1))
                .andExpect(jsonPath("$.data[0].finalTax").value(100000))
                .andExpect(jsonPath("$.data[1].accountId").value(2));

        verify(taxCalculationService).findSnapshots(List.of(1L, 2L));
    }

    @Test
    @DisplayName("계좌 목록이 없는 계좌 id를 넣으면 빈 목록을 돌려준다")
    void 스냅샷_결과없음() throws Exception {
        when(taxCalculationService.findSnapshots(List.of(999L))).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/tax/snapshots").param("accountIds", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("미인증이면 스냅샷 조회도 막힌다")
    void 스냅샷_미인증() throws Exception {
        mockMvc.perform(get("/api/admin/tax/snapshots").param("accountIds", "1"))
                .andExpect(status().isUnauthorized());

        verify(taxCalculationService, never()).findSnapshots(anyList());
    }

    @ParameterizedTest(name = "{0}은 배치를 수동 실행할 수 있다")
    @ValueSource(strings = {"ADMIN", "SETTLEMENT"})
    @DisplayName("정산·관리자만 배치를 수동 실행할 수 있다")
    void 배치_수동실행_허용역할(String role) throws Exception {
        when(taxCalculationService.triggerSnapshotBatch())
                .thenReturn(
                        TaxSnapshotBatchResultResponseDTO.builder()
                                .runId("run-1")
                                .status("REQUESTED")
                                .build());

        mockMvc.perform(post("/api/admin/tax/snapshots/jobs").with(user("tester").roles(role)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("세액 스냅샷 배치 실행 요청 완료"))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @ParameterizedTest(name = "{0}은 배치를 수동 실행할 수 없다")
    @ValueSource(strings = {"REVIEWER", "VIEWER"})
    @DisplayName("심사·조회 역할은 배치 수동 실행이 막힌다")
    void 배치_수동실행_차단역할(String role) throws Exception {
        mockMvc.perform(post("/api/admin/tax/snapshots/jobs").with(user("tester").roles(role)))
                .andExpect(status().isForbidden());

        verify(taxCalculationService, never()).triggerSnapshotBatch();
    }

    @Test
    @WithAnonymousUser
    @DisplayName("미인증이면 배치 수동 실행도 막힌다")
    void 배치_수동실행_미인증() throws Exception {
        mockMvc.perform(post("/api/admin/tax/snapshots/jobs")).andExpect(status().isUnauthorized());

        verify(taxCalculationService, never()).triggerSnapshotBatch();
    }
}
