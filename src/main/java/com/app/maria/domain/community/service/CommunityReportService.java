package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.request.ReportRequestDTO;

public interface CommunityReportService {
    void report(Long reporterId, ReportRequestDTO request);
}
