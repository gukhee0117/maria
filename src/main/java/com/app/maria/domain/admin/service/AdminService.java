package com.app.maria.domain.admin.service;

import com.app.maria.domain.admin.dto.request.AdminLoginRequestDTO;
import com.app.maria.domain.admin.dto.response.AdminLoginResponseDTO;
import com.app.maria.domain.admin.dto.response.AdminMeResponseDTO;
import com.app.maria.domain.admin.dto.response.AdminSummaryResponseDTO;
import com.app.maria.domain.admin.type.AdminRole;
import java.util.List;

public interface AdminService {
    AdminLoginResponseDTO login(AdminLoginRequestDTO request);

    void updateRole(Long actorAdminId, Long targetAdminId, AdminRole newRole);

    AdminLoginResponseDTO refresh(String refreshToken);

    List<AdminSummaryResponseDTO> getAllAdmins();

    AdminMeResponseDTO getMe(Long adminId);
}
