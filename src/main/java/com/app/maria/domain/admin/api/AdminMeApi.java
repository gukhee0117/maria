package com.app.maria.domain.admin.api;

import com.app.maria.domain.admin.dto.response.AdminMeResponseDTO;
import com.app.maria.domain.admin.service.AdminService;
import com.app.maria.global.response.ApiResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/me")
public class AdminMeApi {

    private final AdminService adminService;

    @GetMapping
    public ResponseEntity<ApiResponseDTO<AdminMeResponseDTO>> getMe(
            @AuthenticationPrincipal Long adminId) {
        return ResponseEntity.ok(ApiResponseDTO.of("관리자 정보 조회 성공", adminService.getMe(adminId)));
    }
}
