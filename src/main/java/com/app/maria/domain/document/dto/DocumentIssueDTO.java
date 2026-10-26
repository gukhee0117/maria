package com.app.maria.domain.document.dto;

import com.app.maria.domain.document.type.DocumentStatus;
import com.app.maria.domain.document.type.DocumentType;
import com.app.maria.domain.document.type.IssuedByType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** document_issue 한 행. 목록 조회에서는 fileContent를 읽지 않아 null이다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIssueDTO {
    private Long documentIssueId;
    private Long accountId;
    private Long calcId;
    private DocumentType docType;
    private String issueNo;
    private DocumentStatus status;
    private byte[] fileContent;
    private String fileHash;
    private IssuedByType issuedByType;
    private Long issuedById;
    private LocalDateTime issuedAt;
    private LocalDateTime revokedAt;
}
