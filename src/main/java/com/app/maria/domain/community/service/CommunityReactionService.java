package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.LikeResponseDTO;

public interface CommunityReactionService {
    LikeResponseDTO likePost(Long customerId, Long postId);

    LikeResponseDTO unlikePost(Long customerId, Long postId);

    LikeResponseDTO likeComment(Long customerId, Long commentId);

    LikeResponseDTO unlikeComment(Long customerId, Long commentId);
}
