package com.app.maria.domain.externaltradesync.api;

import com.app.maria.domain.externaltradesync.dto.response.ExternalTradeSyncResultDTO;
import com.app.maria.domain.externaltradesync.service.ExternalTradeSyncService;
import com.app.maria.global.response.ApiResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/external-trade-sync")
public class ExternalTradeSyncApi {

    private final ExternalTradeSyncService externalTradeSyncService;

    @PreAuthorize("hasAnyRole('SETTLEMENT', 'ADMIN')")
    @PostMapping("/jobs")
    public ResponseEntity<ApiResponseDTO<ExternalTradeSyncResultDTO>> executeSync() {
        ExternalTradeSyncResultDTO result = externalTradeSyncService.syncAll();
        return ResponseEntity.ok(
                ApiResponseDTO.of("동기화 완료 · 신규 " + result.getNewJudgementCount() + "건", result));
    }
}
