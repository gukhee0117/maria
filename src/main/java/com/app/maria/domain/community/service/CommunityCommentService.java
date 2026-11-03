package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.request.CommentWriteRequestDTO;
import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.CommentResponseDTO;

public interface CommunityCommentService {
    CommentResponseDTO write(Long customerId, Long postId, CommentWriteRequestDTO request);

    /** 내 댓글의 FAILED 끼루 호출을 다시 PENDING으로 돌려 다시 묻는다. 작성자 본인만, FAILED이고 댓글과 그 글이 보일 때만. */
    CommentResponseDTO retryKkiru(Long customerId, Long commentId);

    /** 본인 댓글 삭제(소프트). 끼루 댓글은 지울 수 없다. */
    void delete(Long customerId, Long commentId);

    /** 글 안의 댓글을 오래된 순으로. 삭제된 댓글은 빼고, 숨김 댓글은 본문 대신 안내 문구로 보낸다. */
    CommentPageResponseDTO list(Long viewerId, Long postId, Long cursor, int size);
}
