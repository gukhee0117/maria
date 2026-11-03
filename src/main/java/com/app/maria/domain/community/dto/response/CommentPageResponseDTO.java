package com.app.maria.domain.community.dto.response;

import java.util.List;

/** 댓글 목록 한 쪽(오래된 순). nextCursor가 null이면 마지막 쪽이다. */
public record CommentPageResponseDTO(List<CommentResponseDTO> items, Long nextCursor) {}
