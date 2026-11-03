package com.app.maria.domain.community.dto.response;

/** 프로필 카드: 닉네임(없으면 null), 내가 쓴 글·댓글 수(삭제 제외), 성향 공개 여부. */
public record MySummaryResponseDTO(
        String nickname, long postCount, long commentCount, boolean investorTypePublic) {}
