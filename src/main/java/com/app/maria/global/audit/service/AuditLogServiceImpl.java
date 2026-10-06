package com.app.maria.global.audit.service;

import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.dto.AuditLogSearchDTO;
import com.app.maria.global.audit.dto.request.AuditLogSearchRequestDTO;
import com.app.maria.global.audit.dto.response.AuditLogResponseDTO;
import com.app.maria.global.audit.mapper.AuditLogMapper;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.app.maria.global.response.PageResponseDTO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponseDTO<AuditLogResponseDTO> searchAuditLogs(
            AuditLogSearchRequestDTO requestDTO) {
        AuditLogSearchDTO searchDTO = requestDTO.toAuditLogSearchDTO();
        List<AuditLogResponseDTO> content =
                auditLogMapper.selectAuditLogs(searchDTO).stream()
                        .map(AuditLogResponseDTO::new)
                        .toList();
        long totalCount = auditLogMapper.countAuditLogs(searchDTO);
        return PageResponseDTO.of(content, totalCount, requestDTO.getPage(), requestDTO.getSize());
    }

    @Override
    public void log(AuditLogDTO auditLogDTO) {
        int insertRows = auditLogMapper.insertLog(auditLogDTO);
        if (insertRows != 1) {
            throw new AppException(ErrorType.AUDIT_LOG_INSERT_FAILED);
        }
    }
}
