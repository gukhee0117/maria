package com.app.maria.domain.community.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 댓글 쓰기. replyToCommentId는 "어느 댓글에 답하는지"이고 비우면 글에 바로 단다. 화면의 {@code @닉네임} 글자는 서버가 읽지 않고 이 값만 쓴다. 본문
 * 길이(1~500자)는 서비스가 정확히 검사한다.
 */
public record CommentWriteRequestDTO(
        @NotNull(message = "내용을 입력해 주세요.") @Size(max = 2000, message = "내용이 너무 깁니다.")
                String content,
        @Positive(message = "답할 댓글 번호가 올바르지 않습니다.") Long replyToCommentId) {}
