package com.app.maria.domain.community.dto.response;

import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.ReportReason;
import com.app.maria.domain.community.type.ReportStatus;
import java.time.LocalDateTime;

/**
 * 관리자 신고 목록 한 줄. 관리자는 숨겨진 원문과 작성자를 확인해야 하므로 고객 화면과 달리 본문 원문과 작성자 ID를 담는다. targetType은 POST 또는
 * COMMENT이고, 댓글이면 postId가 그 댓글이 달린 글이다.
 */
public record AdminReportResponseDTO(
        Long reportId,
        String targetType,
        Long targetId,
        Long postId,
        ReportReason reason,
        ReportStatus status,
        long pendingReportCount,
        CommunityStatus targetStatus,
        String targetContent,
        Long authorCustomerId,
        String authorNickname,
        LocalDateTime createdAt) {}
