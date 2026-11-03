package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.AdminReportProcessResponseDTO;
import com.app.maria.domain.community.dto.response.AdminReportResponseDTO;
import com.app.maria.domain.community.type.ReportAction;
import com.app.maria.domain.community.type.ReportStatus;
import java.util.List;

public interface CommunityReportAdminService {
    List<AdminReportResponseDTO> list(ReportStatus status, int size);

    AdminReportProcessResponseDTO process(Long reportId, ReportAction action);
}
