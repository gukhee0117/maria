package com.app.maria.domain.community.repository;

import com.app.maria.domain.community.entity.CommunityPostReactionEntity;
import com.app.maria.domain.community.entity.CommunityPostReactionId;
import com.app.maria.domain.community.type.ReactionType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostReactionRepository
        extends JpaRepository<CommunityPostReactionEntity, CommunityPostReactionId> {

    @Query(
            """
            select r.communityPostId as id, count(r) as total
            from CommunityPostReactionEntity r
            where r.communityPostId in :postIds
              and r.reactionType = com.app.maria.domain.community.type.ReactionType.LIKE
            group by r.communityPostId
            """)
    List<CommunityIdCount> countLikesByPostIds(@Param("postIds") Collection<Long> postIds);

    /** 내가 좋아요를 누른 글 id만 골라 낸다. */
    @Query(
            """
            select r.communityPostId from CommunityPostReactionEntity r
            where r.customerId = :customerId
              and r.reactionType = com.app.maria.domain.community.type.ReactionType.LIKE
              and r.communityPostId in :postIds
            """)
    List<Long> findLikedPostIds(
            @Param("customerId") Long customerId, @Param("postIds") Collection<Long> postIds);

    /** 없는 좋아요를 취소해도 오류 없이 0건으로 끝나게 직접 삭제한다. */
    @Modifying
    @Query(
            "delete from CommunityPostReactionEntity r where r.communityPostId = :postId"
                    + " and r.customerId = :customerId and r.reactionType = :type")
    int deleteReaction(
            @Param("postId") Long postId,
            @Param("customerId") Long customerId,
            @Param("type") ReactionType type);
}
