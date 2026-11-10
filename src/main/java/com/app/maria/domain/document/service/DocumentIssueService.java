package com.app.maria.domain.document.service;

import com.app.maria.domain.document.dto.DocumentFileDTO;
import com.app.maria.domain.document.dto.response.DocumentIssueResponseDTO;
import com.app.maria.domain.document.type.DocumentType;
import java.util.List;

public interface DocumentIssueService {
    DocumentIssueResponseDTO issueForCustomer(Long customerId, DocumentType docType);

    DocumentIssueResponseDTO issueForAdmin(Long accountId, DocumentType docType);

    List<DocumentIssueResponseDTO> getByCustomer(Long customerId);

    List<DocumentIssueResponseDTO> getByAccount(Long accountId);

    DocumentFileDTO downloadForCustomer(Long customerId, Long documentIssueId);

    DocumentFileDTO downloadForAdmin(Long documentIssueId);
}
