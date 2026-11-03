package com.app.maria.domain.community.dto.request;

import com.app.maria.domain.community.type.ReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 신고. 글 또는 댓글 중 정확히 하나만 지정한다(서비스가 검사). */
public record ReportRequestDTO(
        @Positive(message = "글 번호가 올바르지 않습니다.") Long postId,
        @Positive(message = "댓글 번호가 올바르지 않습니다.") Long commentId,
        @NotNull(message = "신고 사유를 선택해 주세요.") ReportReason reason) {}
