package com.app.maria.global.audit.dto.response;

import com.app.maria.global.audit.dto.AuditLogDTO;
import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class AuditLogResponseDTO {

    private Long auditId;
    private Long adminId;
    private String targetTable;
    private String targetPk;
    private String beforeValue;
    private String afterValue;
    private String reasonCode;
    private LocalDateTime processedAt;
    private LocalDateTime recordedAt;
    private String adminName;
    private String adminRole;

    private String targetName;

    public AuditLogResponseDTO(AuditLogDTO dto) {
        this.auditId = dto.getAuditId();
        this.adminId = dto.getAdminId();
        this.targetTable = dto.getTargetTable();
        this.targetPk = dto.getTargetPk();
        this.beforeValue = dto.getBeforeValue();
        this.afterValue = dto.getAfterValue();
        this.reasonCode = dto.getReasonCode();
        this.processedAt = dto.getProcessedAt();
        this.recordedAt = dto.getRecordedAt();
        this.adminName = dto.getAdminName();
        this.adminRole = dto.getAdminRole();
        this.targetName =
                switch (dto.getTargetTable()) {
                    case "ADMIN_USER" -> dto.getTargetAdminName();
                    case "ACCOUNT" -> dto.getTargetOwnerAccountNo();
                    case "SETTLEMENT_BATCH" ->
                            dto.getTargetBatchExecutedAt() != null
                                    ? dto.getTargetBatchExecutedAt().toLocalDate().toString()
                                    : dto.getTargetPk();
                    case "SETTLEMENT_ITEM" -> dto.getTargetPk();
                    default -> dto.getTargetTable();
                };
    }
}
