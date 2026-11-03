package com.app.maria.domain.community.dto.response;

import com.app.maria.domain.community.type.CommunityStatus;

/** 신고 처리 결과. 같은 대상의 처리 대기 신고는 한꺼번에 닫히므로 닫은 개수를 함께 알려 준다. */
public record AdminReportProcessResponseDTO(
        String targetType, Long targetId, CommunityStatus targetStatus, int processedReportCount) {}
