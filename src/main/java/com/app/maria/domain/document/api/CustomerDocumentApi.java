package com.app.maria.domain.document.api;

import com.app.maria.domain.document.dto.response.DocumentIssueResponseDTO;
import com.app.maria.domain.document.service.DocumentIssueService;
import com.app.maria.global.response.ApiResponseDTO;
import com.app.maria.global.security.customer.CustomerPrincipal;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/customer/documents")
public class CustomerDocumentApi {

    private final DocumentIssueService documentIssueService;

    @PostMapping
    public ResponseEntity<ApiResponseDTO<DocumentIssueResponseDTO>> issue(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "증빙서류 발급 완료",
                        documentIssueService.issueForCustomer(principal.customerId())));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDTO<List<DocumentIssueResponseDTO>>> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponseDTO.of(
                        "증빙서류 목록 조회 성공",
                        documentIssueService.getByCustomer(principal.customerId())));
    }

    @GetMapping("/{documentIssueId}/download")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal(errorOnInvalidType = true) CustomerPrincipal principal,
            @PathVariable @Positive Long documentIssueId) {
        return DocumentResponses.pdf(
                documentIssueService.downloadForCustomer(principal.customerId(), documentIssueId));
    }
}
