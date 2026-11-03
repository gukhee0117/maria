package com.app.maria.domain.community.dto.response;

import java.util.List;

/** 글 목록 한 쪽. nextCursor가 null이면 마지막 쪽이다. 다음 쪽은 cursor=nextCursor로 부른다. */
public record PostPageResponseDTO(List<PostResponseDTO> items, Long nextCursor) {}
