package com.app.maria.domain.community.dto.response;

/** 좋아요를 켜거나 끈 결과. liked는 지금 내가 눌러 둔 상태이고 likeCount는 전체 개수다. */
public record LikeResponseDTO(boolean liked, long likeCount) {}
