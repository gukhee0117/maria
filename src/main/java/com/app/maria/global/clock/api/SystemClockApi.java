package com.app.maria.global.clock.api;

import com.app.maria.global.clock.dto.request.SystemClockChangeRequestDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.clock.service.SystemClockManagementService;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/system-clock")
public class SystemClockApi {

    private final SystemClockManagementService systemClockManagementService;
    private final BusinessClockService businessClockService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SETTLEMENT')")
    @PatchMapping
    public ResponseEntity<?> changeSystemTime(
            @AuthenticationPrincipal Long adminId,
            @Valid @RequestBody SystemClockChangeRequestDTO requestDTO) {
        LocalDateTime changedDatetime =
                systemClockManagementService.changeSystemTime(adminId, requestDTO);
        return ResponseEntity.ok(ApiResponseDTO.of("시스템 업무시각 변경 완료", changedDatetime));
    }

    @GetMapping
    public ResponseEntity<?> getSystemTime() {
        LocalDateTime currentDatetime = businessClockService.now();

        return ResponseEntity.ok(ApiResponseDTO.of("시스템 업무시각 조회 완료", currentDatetime));
    }
}
