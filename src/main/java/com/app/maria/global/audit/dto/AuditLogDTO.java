package com.app.maria.global.audit.dto;

import java.time.LocalDateTime;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class AuditLogDTO {

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

    private String targetAdminName;
    private String targetOwnerAccountNo;
    private LocalDateTime targetBatchExecutedAt;
}
