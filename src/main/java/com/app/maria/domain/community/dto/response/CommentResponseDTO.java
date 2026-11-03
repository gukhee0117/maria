package com.app.maria.domain.community.dto.response;

import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import java.time.LocalDateTime;

/**
 * 댓글 한 건. 작성자 ID는 담지 않는다. 끼루 댓글은 authorType이 AI이고 닉네임이 "끼루 AI"다. replyToNickname은 답하는 대상 댓글 작성자의 현재
 * 닉네임이고, 대상이 없거나 삭제됐으면 null이다. postAuthor는 이 글을 쓴 사람이 단 댓글인지(서버가 계산, 저장 안 함).
 */
public record CommentResponseDTO(
        Long id,
        Long postId,
        AuthorType authorType,
        String authorNickname,
        String authorInvestorType,
        boolean mine,
        boolean postAuthor,
        String content,
        CommunityStatus status,
        long likeCount,
        boolean likedByMe,
        Long replyToCommentId,
        String replyToNickname,
        KkiruStatus kkiruStatus,
        String sourcesJson,
        LocalDateTime createdAt) {}
