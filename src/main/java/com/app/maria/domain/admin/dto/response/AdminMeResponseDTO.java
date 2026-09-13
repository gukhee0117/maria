package com.app.maria.domain.admin.dto.response;

import com.app.maria.domain.admin.type.AdminRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminMeResponseDTO {
    private Long adminId;
    private String name;
    private AdminRole role;
}
