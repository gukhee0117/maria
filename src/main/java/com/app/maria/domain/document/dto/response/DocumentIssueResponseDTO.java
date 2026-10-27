package com.app.maria.domain.document.dto.response;

import com.app.maria.domain.document.dto.DocumentIssueDTO;
import com.app.maria.domain.document.type.DocumentStatus;
import com.app.maria.domain.document.type.DocumentType;
import com.app.maria.domain.document.type.IssuedByType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIssueResponseDTO {
    private Long documentIssueId;
    private Long accountId;
    private DocumentType docType;
    private String issueNo;
    private DocumentStatus status;
    private IssuedByType issuedByType;
    private LocalDateTime issuedAt;
    private LocalDateTime revokedAt;

    public static DocumentIssueResponseDTO of(DocumentIssueDTO issue) {
        return DocumentIssueResponseDTO.builder()
                .documentIssueId(issue.getDocumentIssueId())
                .accountId(issue.getAccountId())
                .docType(issue.getDocType())
                .issueNo(issue.getIssueNo())
                .status(issue.getStatus())
                .issuedByType(issue.getIssuedByType())
                .issuedAt(issue.getIssuedAt())
                .revokedAt(issue.getRevokedAt())
                .build();
    }
}
