package com.app.maria.global.audit.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@Builder
public class AuditLogSearchDTO {

    private String targetTable;

    private String adminKeyword;
    private List<String> matchedRoles;

    private String targetKeyword;

    private String reasonKeyword;
    private List<String> matchedReasonCodes;
    private List<String> knownReasonCodes;

    private LocalDateTime startDate;
    private LocalDateTime endDate;

    @Builder.Default private String orderBy = "al.processed_at DESC, al.audit_id DESC";
    private int size;
    private int offset;
}
