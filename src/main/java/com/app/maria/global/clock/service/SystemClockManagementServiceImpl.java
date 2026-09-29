package com.app.maria.global.clock.service;

import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.dto.SystemClockDTO;
import com.app.maria.global.clock.dto.request.SystemClockChangeRequestDTO;
import com.app.maria.global.clock.mapper.SystemClockMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SystemClockManagementServiceImpl implements SystemClockManagementService {

    private final SystemClockMapper systemClockMapper;
    private final AuditLogService auditLogService;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LocalDateTime changeSystemTime(Long adminId, SystemClockChangeRequestDTO requestDTO) {
        LocalDateTime newDatetime = requestDTO.getNewDatetime();
        String reasonCode = requestDTO.getReasonCode();

        SystemClockDTO currentClock =
                systemClockMapper
                        .selectSystemClock()
                        .orElseThrow(
                                () -> new AppException(ErrorType.SYSTEM_CLOCK_NOT_INITIALIZED));
        if (currentClock.getCurrentDatetime().equals(newDatetime)) {
            return currentClock.getCurrentDatetime();
        }

        int updatedRows =
                systemClockMapper.updateSystemClock(
                        newDatetime, currentClock.getReferenceRealDatetime());

        if (updatedRows != 1) {
            throw new AppException(ErrorType.SYSTEM_CLOCK_UPDATE_CONFLICT, adminId);
        }

        AuditLogDTO auditLog =
                AuditLogDTO.builder()
                        .adminId(adminId)
                        .targetTable("SYSTEM_CLOCK")
                        .targetPk("1")
                        .beforeValue(currentClock.getCurrentDatetime().toString())
                        .afterValue(newDatetime.toString())
                        .reasonCode(reasonCode)
                        .build();
        auditLogService.log(auditLog);

        return newDatetime;
    }
}
