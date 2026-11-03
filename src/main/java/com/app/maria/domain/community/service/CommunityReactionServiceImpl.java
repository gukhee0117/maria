package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.LikeResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityCommentLikeEntity;
import com.app.maria.domain.community.entity.CommunityCommentLikeId;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.entity.CommunityPostReactionEntity;
import com.app.maria.domain.community.entity.CommunityPostReactionId;
import com.app.maria.domain.community.repository.CommunityCommentLikeRepository;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityIdCount;
import com.app.maria.domain.community.repository.CommunityPostReactionRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.ReactionType;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 글·댓글 좋아요. 켜기·끄기 모두 같은 요청을 여러 번 보내도 결과가 같다(멱등). 같은 글·댓글에 동시에 누르는 요청은 대상 행을 잠가 한 번에 하나씩 처리한다. */
@Service
@RequiredArgsConstructor
public class CommunityReactionServiceImpl implements CommunityReactionService {
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostReactionRepository postReactionRepository;
    private final CommunityCommentLikeRepository commentLikeRepository;
    private final BusinessClockService clock;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public LikeResponseDTO likePost(Long customerId, Long postId) {
        CommunityPostEntity post = lockPost(postId);
        if (!post.isVisible()) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_VISIBLE, postId);
        }
        if (!postReactionRepository.existsById(
                new CommunityPostReactionId(postId, customerId, ReactionType.LIKE))) {
            postReactionRepository.save(
                    CommunityPostReactionEntity.of(
                            postId, customerId, ReactionType.LIKE, clock.now()));
        }
        return new LikeResponseDTO(true, postLikeCount(postId));
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public LikeResponseDTO unlikePost(Long customerId, Long postId) {
        lockPost(postId);
        postReactionRepository.deleteReaction(postId, customerId, ReactionType.LIKE);
        return new LikeResponseDTO(false, postLikeCount(postId));
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public LikeResponseDTO likeComment(Long customerId, Long commentId) {
        CommunityCommentEntity comment = lockComment(commentId);
        if (!comment.isVisible()) {
            throw new AppException(ErrorType.COMMUNITY_COMMENT_NOT_FOUND, commentId);
        }
        boolean postVisible =
                postRepository
                        .findById(comment.getCommunityPostId())
                        .map(CommunityPostEntity::isVisible)
                        .orElse(false);
        if (!postVisible) {
            throw new AppException(
                    ErrorType.COMMUNITY_POST_NOT_VISIBLE, comment.getCommunityPostId());
        }
        if (!commentLikeRepository.existsById(new CommunityCommentLikeId(commentId, customerId))) {
            commentLikeRepository.save(
                    CommunityCommentLikeEntity.of(commentId, customerId, clock.now()));
        }
        return new LikeResponseDTO(true, commentLikeCount(commentId));
    }

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public LikeResponseDTO unlikeComment(Long customerId, Long commentId) {
        lockComment(commentId);
        commentLikeRepository.deleteLike(commentId, customerId);
        return new LikeResponseDTO(false, commentLikeCount(commentId));
    }

    private CommunityPostEntity lockPost(Long postId) {
        return postRepository
                .findByIdForUpdate(postId)
                .orElseThrow(() -> new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId));
    }

    private CommunityCommentEntity lockComment(Long commentId) {
        return commentRepository
                .findByIdForUpdate(commentId)
                .orElseThrow(
                        () -> new AppException(ErrorType.COMMUNITY_COMMENT_NOT_FOUND, commentId));
    }

    private long postLikeCount(Long postId) {
        return postReactionRepository.countLikesByPostIds(List.of(postId)).stream()
                .findFirst()
                .map(CommunityIdCount::getTotal)
                .orElse(0L);
    }

    private long commentLikeCount(Long commentId) {
        return commentLikeRepository.countByCommentIds(List.of(commentId)).stream()
                .findFirst()
                .map(CommunityIdCount::getTotal)
                .orElse(0L);
    }
}
