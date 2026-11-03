package com.app.maria.domain.community.dto.response;

import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import java.time.LocalDateTime;

/**
 * 글 한 건. 작성자 ID는 담지 않고 닉네임만 담는다. 숨김·삭제된 글은 본문 대신 안내 문구가 들어간다. authorInvestorType은 작성자가 성향 공개에 동의했을
 * 때만 값이 있다.
 */
public record PostResponseDTO(
        Long id,
        String ticker,
        String authorNickname,
        String authorInvestorType,
        boolean mine,
        String content,
        CommunityStatus status,
        long likeCount,
        long commentCount,
        boolean likedByMe,
        KkiruStatus kkiruStatus,
        boolean edited,
        boolean editable,
        LocalDateTime createdAt) {}
