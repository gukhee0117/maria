package com.app.maria.global.clock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.app.maria.global.audit.dto.AuditLogDTO;

import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.dto.SystemClockDTO;
import com.app.maria.global.clock.dto.request.SystemClockChangeRequestDTO;
import com.app.maria.global.clock.mapper.SystemClockMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemClockManagementServiceImplTest {

    @Mock private SystemClockMapper systemClockMapper;

    @Mock private AuditLogService auditLogService;

    @InjectMocks private SystemClockManagementServiceImpl systemClockManagementService;

    @Test
    void 시스템_시계가_초기화되지_않으면_예외가_발생한다() {
        when(systemClockMapper.selectSystemClock()).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                systemClockManagementService.changeSystemTime(
                                        1L,
                                        request(
                                                LocalDateTime.of(2027, 8, 5, 9, 0),
                                                "DEMO_TIME_CHANGE")))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.SYSTEM_CLOCK_NOT_INITIALIZED.getMessage());

        verify(systemClockMapper).selectSystemClock();
        verify(systemClockMapper, never())
                .updateSystemClock(any(LocalDateTime.class), any(LocalDateTime.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void 기존_시간과_같으면_변경과_감사로그_저장을_하지_않는다() {
        LocalDateTime currentDatetime = LocalDateTime.of(2026, 8, 5, 10, 0);

        SystemClockDTO currentClock =
                new SystemClockDTO(1L, currentDatetime, currentDatetime, currentDatetime);

        when(systemClockMapper.selectSystemClock()).thenReturn(Optional.of(currentClock));

        LocalDateTime result =
                systemClockManagementService.changeSystemTime(
                        1L, request(currentDatetime, "DEMO_TIME_CHANGE"));

        assertThat(result).isEqualTo(currentDatetime);
        verify(systemClockMapper).selectSystemClock();
        verify(systemClockMapper, never())
                .updateSystemClock(any(LocalDateTime.class), any(LocalDateTime.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void 업무시각을_변경하고_감사로그를_저장한다() {
        Long adminId = 10L;
        LocalDateTime currentDatetime = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime newDatetime = LocalDateTime.of(2027, 8, 5, 9, 0);
        String reasonCode = "DEMO_TIME_CHANGE";

        SystemClockDTO currentClock =
                new SystemClockDTO(1L, currentDatetime, currentDatetime, currentDatetime);

        when(systemClockMapper.selectSystemClock()).thenReturn(Optional.of(currentClock));
        when(systemClockMapper.updateSystemClock(newDatetime, currentDatetime)).thenReturn(1);
        LocalDateTime result =
                systemClockManagementService.changeSystemTime(
                        adminId, request(newDatetime, reasonCode));

        ArgumentCaptor<AuditLogDTO> auditLogCaptor = ArgumentCaptor.forClass(AuditLogDTO.class);

        verify(auditLogService).log(auditLogCaptor.capture());

        AuditLogDTO savedLog = auditLogCaptor.getValue();

        assertThat(savedLog.getAdminId()).isEqualTo(adminId);
        assertThat(savedLog.getTargetTable()).isEqualTo("SYSTEM_CLOCK");
        assertThat(savedLog.getTargetPk()).isEqualTo("1");
        assertThat(savedLog.getBeforeValue()).isEqualTo(currentDatetime.toString());
        assertThat(savedLog.getAfterValue()).isEqualTo(newDatetime.toString());
        assertThat(savedLog.getReasonCode()).isEqualTo(reasonCode);
        assertThat(savedLog.getProcessedAt()).isNull();
        assertThat(result).isEqualTo(newDatetime);

        InOrder callOrder = inOrder(systemClockMapper, auditLogService);

        callOrder.verify(systemClockMapper).selectSystemClock();
        callOrder.verify(systemClockMapper).updateSystemClock(newDatetime, currentDatetime);
        callOrder.verify(auditLogService).log(any(AuditLogDTO.class));
    }

    @Test
    void 시스템_시계_UPDATE가_실패하면_감사로그를_저장하지_않는다() {
        LocalDateTime currentDatetime = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime newDatetime = LocalDateTime.of(2027, 8, 5, 9, 0);

        when(systemClockMapper.selectSystemClock())
                .thenReturn(
                        Optional.of(
                                new SystemClockDTO(
                                        1L, currentDatetime, currentDatetime, currentDatetime)));
        when(systemClockMapper.updateSystemClock(newDatetime, currentDatetime)).thenReturn(0);

        assertThatThrownBy(
                        () ->
                                systemClockManagementService.changeSystemTime(
                                        1L, request(newDatetime, "DEMO_TIME_CHANGE")))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.SYSTEM_CLOCK_UPDATE_CONFLICT.getMessage());

        verify(systemClockMapper).updateSystemClock(newDatetime, currentDatetime);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void 감사로그_INSERT가_실패하면_예외가_발생한다() {
        LocalDateTime currentDatetime = LocalDateTime.of(2026, 8, 5, 10, 0);
        LocalDateTime newDatetime = LocalDateTime.of(2027, 8, 5, 9, 0);

        when(systemClockMapper.selectSystemClock())
                .thenReturn(
                        Optional.of(
                                new SystemClockDTO(
                                        1L, currentDatetime, currentDatetime, currentDatetime)));
        when(systemClockMapper.updateSystemClock(newDatetime, currentDatetime)).thenReturn(1);
        doThrow(new AppException(ErrorType.AUDIT_LOG_INSERT_FAILED))
                .when(auditLogService)
                .log(any(AuditLogDTO.class));

        assertThatThrownBy(
                        () ->
                                systemClockManagementService.changeSystemTime(
                                        1L, request(newDatetime, "DEMO_TIME_CHANGE")))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorType.AUDIT_LOG_INSERT_FAILED.getMessage());

        verify(systemClockMapper).updateSystemClock(newDatetime, currentDatetime);
        verify(auditLogService).log(any(AuditLogDTO.class));
    }

    private static SystemClockChangeRequestDTO request(
            LocalDateTime newDatetime, String reasonCode) {
        return new SystemClockChangeRequestDTO(newDatetime, reasonCode);
    }
}
