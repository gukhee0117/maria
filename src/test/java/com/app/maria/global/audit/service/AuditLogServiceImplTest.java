package com.app.maria.global.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.dto.AuditLogSearchDTO;
import com.app.maria.global.audit.dto.request.AuditLogSearchRequestDTO;
import com.app.maria.global.audit.dto.response.AuditLogResponseDTO;

import com.app.maria.global.audit.mapper.AuditLogMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock AuditLogMapper auditLogMapper;

    @InjectMocks AuditLogServiceImpl auditLogService;

    private AuditLogDTO auditLog(Long auditId, Long adminId, String targetTable, String targetPk) {
        return AuditLogDTO.builder()
                .auditId(auditId)
                .adminId(adminId)
                .targetTable(targetTable)
                .targetPk(targetPk)
                .beforeValue("VIEWER")
                .afterValue("ADMIN")
                .reasonCode("ADMIN_ROLE_UPDATE")
                .processedAt(LocalDateTime.of(2026, 8, 10, 10, 0))
                .build();
    }

    @Test
    @DisplayName("검색 조건을 VO로 변환해 Mapper에 넘기고, 결과를 PageResponseDTO로 변환해 반환한다")
    void searchAuditLogsConvertsRequestToVoAndMapsResultToPageResponseDto() {
        AuditLogSearchRequestDTO request =
                AuditLogSearchRequestDTO.builder().targetTable("ADMIN_USER").build();

        when(auditLogMapper.selectAuditLogs(any(AuditLogSearchDTO.class)))
                .thenReturn(List.of(auditLog(10L, 1L, "ADMIN_USER", "2")));
        when(auditLogMapper.countAuditLogs(any(AuditLogSearchDTO.class))).thenReturn(1L);

        PageResponseDTO<AuditLogResponseDTO> result = auditLogService.searchAuditLogs(request);

        assertThat(result.getContent()).hasSize(1);
        AuditLogResponseDTO response = result.getContent().get(0);
        assertThat(response.getAuditId()).isEqualTo(10L);
        assertThat(response.getAdminId()).isEqualTo(1L);
        assertThat(response.getTargetTable()).isEqualTo("ADMIN_USER");
        assertThat(response.getTargetPk()).isEqualTo("2");
        assertThat(result.getTotalCount()).isEqualTo(1L);
        assertThat(result.getPage()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(20);

        ArgumentCaptor<AuditLogSearchDTO> captor = ArgumentCaptor.forClass(AuditLogSearchDTO.class);
        verify(auditLogMapper).selectAuditLogs(captor.capture());
        AuditLogSearchDTO passedVo = captor.getValue();
        assertThat(passedVo.getTargetTable()).isEqualTo("ADMIN_USER");
    }

    @Test
    @DisplayName("adminKeyword/reasonKeyword를 한글 라벨과 대조해 role/reasonCode 코드 목록으로 변환해 Mapper에 전달한다")
    void searchAuditLogsResolvesKeywordsIntoMatchedCodeLists() {
        AuditLogSearchRequestDTO request =
                AuditLogSearchRequestDTO.builder()
                        .adminKeyword("최고관리자")
                        .reasonKeyword("관리자 권한 변경")
                        .build();
        when(auditLogMapper.selectAuditLogs(any())).thenReturn(List.of());
        when(auditLogMapper.countAuditLogs(any())).thenReturn(0L);

        auditLogService.searchAuditLogs(request);

        ArgumentCaptor<AuditLogSearchDTO> captor = ArgumentCaptor.forClass(AuditLogSearchDTO.class);
        verify(auditLogMapper).selectAuditLogs(captor.capture());
        AuditLogSearchDTO passedVo = captor.getValue();
        assertThat(passedVo.getAdminKeyword()).isEqualTo("최고관리자");
        assertThat(passedVo.getMatchedRoles()).containsExactly("ADMIN");
        assertThat(passedVo.getReasonKeyword()).isEqualTo("관리자 권한 변경");
        assertThat(passedVo.getMatchedReasonCodes()).containsExactly("ADMIN_ROLE_UPDATE");
        assertThat(passedVo.getKnownReasonCodes()).contains("ADMIN_ROLE_UPDATE", "ACCOUNT_APPLY");
    }

    @Test
    @DisplayName("계좌 해지 한글 사유를 감사로그 사유코드로 변환한다")
    void searchAuditLogsResolvesAccountClosureReasonLabel() {
        AuditLogSearchRequestDTO request =
                AuditLogSearchRequestDTO.builder().reasonKeyword("계좌 해지 반려").build();
        when(auditLogMapper.selectAuditLogs(any())).thenReturn(List.of());
        when(auditLogMapper.countAuditLogs(any())).thenReturn(0L);

        auditLogService.searchAuditLogs(request);

        ArgumentCaptor<AuditLogSearchDTO> captor = ArgumentCaptor.forClass(AuditLogSearchDTO.class);
        verify(auditLogMapper).selectAuditLogs(captor.capture());
        assertThat(captor.getValue().getMatchedReasonCodes())
                .containsExactly("ACCOUNT_CLOSURE_REJECTED");
    }

    @Test
    @DisplayName("knownReasonCodes는 reasonKeyword가 비어있어도 항상 전체 사유코드 목록으로 채워진다")
    void searchAuditLogsAlwaysPassesFullKnownReasonCodesRegardlessOfKeyword() {
        AuditLogSearchRequestDTO request = AuditLogSearchRequestDTO.builder().build();
        when(auditLogMapper.selectAuditLogs(any())).thenReturn(List.of());
        when(auditLogMapper.countAuditLogs(any())).thenReturn(0L);

        auditLogService.searchAuditLogs(request);

        ArgumentCaptor<AuditLogSearchDTO> captor = ArgumentCaptor.forClass(AuditLogSearchDTO.class);
        verify(auditLogMapper).selectAuditLogs(captor.capture());
        assertThat(captor.getValue().getKnownReasonCodes())
                .contains("ADMIN_ROLE_UPDATE", "ACCOUNT_APPLY", "SETTLEMENT_BATCH_REQUESTED");
    }

    @Test
    @DisplayName("adminKeyword/reasonKeyword가 비어있으면 매치되는 코드 목록도 전부 빈 리스트다")
    void searchAuditLogsProducesEmptyMatchedListsWhenKeywordsBlank() {
        AuditLogSearchRequestDTO request = AuditLogSearchRequestDTO.builder().build();
        when(auditLogMapper.selectAuditLogs(any())).thenReturn(List.of());
        when(auditLogMapper.countAuditLogs(any())).thenReturn(0L);

        auditLogService.searchAuditLogs(request);

        ArgumentCaptor<AuditLogSearchDTO> captor = ArgumentCaptor.forClass(AuditLogSearchDTO.class);
        verify(auditLogMapper).selectAuditLogs(captor.capture());
        AuditLogSearchDTO passedVo = captor.getValue();
        assertThat(passedVo.getMatchedRoles()).isEmpty();
        assertThat(passedVo.getMatchedReasonCodes()).isEmpty();
    }

    @Test
    @DisplayName("page/size로 offset을 계산해 Mapper에 전달한다")
    void searchAuditLogsComputesOffsetFromPageAndSize() {
        AuditLogSearchRequestDTO request =
                AuditLogSearchRequestDTO.builder().page(2).size(10).build();
        when(auditLogMapper.selectAuditLogs(any())).thenReturn(List.of());
        when(auditLogMapper.countAuditLogs(any())).thenReturn(0L);

        auditLogService.searchAuditLogs(request);

        ArgumentCaptor<AuditLogSearchDTO> captor = ArgumentCaptor.forClass(AuditLogSearchDTO.class);
        verify(auditLogMapper).selectAuditLogs(captor.capture());
        assertThat(captor.getValue().getOffset()).isEqualTo(20);
        assertThat(captor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("전체 건수는 content 크기와 무관하게 Mapper의 count 결과를 그대로 쓴다")
    void searchAuditLogsReturnsTotalCountFromMapperRegardlessOfContentSize() {
        AuditLogSearchRequestDTO request = AuditLogSearchRequestDTO.builder().build();
        when(auditLogMapper.selectAuditLogs(any()))
                .thenReturn(List.of(auditLog(1L, 1L, "ADMIN_USER", "2")));
        when(auditLogMapper.countAuditLogs(any())).thenReturn(50L);

        PageResponseDTO<AuditLogResponseDTO> result = auditLogService.searchAuditLogs(request);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalCount()).isEqualTo(50L);
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 content와 0건을 반환한다")
    void searchAuditLogsReturnsEmptyContentWhenNoResults() {
        AuditLogSearchRequestDTO request = AuditLogSearchRequestDTO.builder().build();
        when(auditLogMapper.selectAuditLogs(any())).thenReturn(List.of());
        when(auditLogMapper.countAuditLogs(any())).thenReturn(0L);

        PageResponseDTO<AuditLogResponseDTO> result = auditLogService.searchAuditLogs(request);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalCount()).isZero();
    }

    @Test
    @DisplayName("insert가 1건 성공하면 예외 없이 정상 종료한다")
    void logInsertsSuccessfullyWhenOneRowAffected() {
        AuditLogDTO auditLog = auditLog(null, 1L, "ADMIN_USER", "2");
        when(auditLogMapper.insertLog(auditLog)).thenReturn(1);

        auditLogService.log(auditLog);

        verify(auditLogMapper).insertLog(auditLog);
    }

    @Test
    @DisplayName("insert된 행이 1건이 아니면 AuditLogInsertException을 던진다")
    void logThrowsAuditLogInsertExceptionWhenInsertedRowsIsNotOne() {
        AuditLogDTO auditLog = auditLog(null, 1L, "ADMIN_USER", "2");
        when(auditLogMapper.insertLog(auditLog)).thenReturn(0);

        assertThatThrownBy(() -> auditLogService.log(auditLog))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.AUDIT_LOG_INSERT_FAILED.getMessage());
    }
}
