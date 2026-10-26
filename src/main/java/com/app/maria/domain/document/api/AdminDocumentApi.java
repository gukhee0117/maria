package com.app.maria.domain.document.api;

import com.app.maria.domain.document.dto.response.DocumentIssueResponseDTO;
import com.app.maria.domain.document.service.DocumentIssueService;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/admin/documents")
public class AdminDocumentApi {

    private final DocumentIssueService documentIssueService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT')")
    @PostMapping("/accounts/{accountId}")
    public ResponseEntity<ApiResponseDTO<DocumentIssueResponseDTO>> issue(
            @PathVariable @Positive Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("증빙서류 발급 완료", documentIssueService.issueForAdmin(accountId)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT', 'REVIEWER', 'VIEWER')")
    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<ApiResponseDTO<List<DocumentIssueResponseDTO>>> list(
            @PathVariable @Positive Long accountId) {
        return ResponseEntity.ok(
                ApiResponseDTO.of("증빙서류 목록 조회 성공", documentIssueService.getByAccount(accountId)));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT')")
    @GetMapping("/{documentIssueId}/download")
    public ResponseEntity<byte[]> download(@PathVariable @Positive Long documentIssueId) {
        return DocumentResponses.pdf(documentIssueService.downloadForAdmin(documentIssueId));
    }
}
