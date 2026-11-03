package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.request.PostUpdateRequestDTO;
import com.app.maria.domain.community.dto.request.PostWriteRequestDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.dto.response.PostResponseDTO;

public interface CommunityPostService {
    /** 목록 조회에서 종목 없는 글을 뜻하는 ticker 값. */
    String GENERAL = "GENERAL";

    /** 글을 쓴다. 본문에 @끼루가 있으면 호출 상태를 함께 저장하고, PENDING이면 이벤트를 낸다. */
    PostResponseDTO write(Long customerId, PostWriteRequestDTO request);

    /** 본인 글 본문 수정. 끼루가 답했거나 답을 만드는 중인 글은 고칠 수 없다. */
    PostResponseDTO edit(Long customerId, Long postId, PostUpdateRequestDTO request);

    /** 내 글의 FAILED 끼루 호출을 다시 PENDING으로 돌려 다시 묻는다. 작성자 본인만, FAILED이고 보이는 글일 때만. */
    PostResponseDTO retryKkiru(Long customerId, Long postId);

    /** 본인 글 삭제(소프트). */
    void delete(Long customerId, Long postId);

    /** 종목 화면 목록. ticker가 비면 전체, GENERAL이면 종목 없는 글, 아니면 그 종목. */
    PostPageResponseDTO list(Long viewerId, String ticker, String keyword, Long cursor, int size);

    /** 인기글: 최근 며칠 안에 쓴 글 중 댓글이 많은 순. ticker가 비면 전체 종목. */
    PostPageResponseDTO popular(Long viewerId, String ticker, int size);

    /** 글 한 건. 삭제된 글은 보이는 댓글이 있을 때만 안내 문구로 보이고, 없으면 찾을 수 없다. */
    PostResponseDTO detail(Long viewerId, Long postId);
}
