package com.app.maria.domain.admin.api;

import com.app.maria.domain.admin.dto.request.AdminRoleUpdateRequestDTO;
import com.app.maria.domain.admin.dto.response.AdminSummaryResponseDTO;
import com.app.maria.domain.admin.service.AdminService;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/admins")
public class AdminManagementApi {

    private final AdminService adminService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponseDTO<List<AdminSummaryResponseDTO>>> getAllAdmins() {
        return ResponseEntity.ok(ApiResponseDTO.of("조회 성공", adminService.getAllAdmins()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{adminId}/role")
    public ResponseEntity<ApiResponseDTO<Void>> updateRole(
            @AuthenticationPrincipal Long actorAdminId,
            @PathVariable Long adminId,
            @Valid @RequestBody AdminRoleUpdateRequestDTO request) {
        adminService.updateRole(actorAdminId, adminId, request.getRole());
        return ResponseEntity.ok(ApiResponseDTO.of("역할이 변경되었습니다."));
    }
}
