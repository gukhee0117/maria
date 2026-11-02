package com.app.maria.domain.community.repository;

import com.app.maria.domain.community.entity.CommunityCommentLikeEntity;
import com.app.maria.domain.community.entity.CommunityCommentLikeId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityCommentLikeRepository
        extends JpaRepository<CommunityCommentLikeEntity, CommunityCommentLikeId> {

    @Query(
            """
            select l.communityCommentId as id, count(l) as total
            from CommunityCommentLikeEntity l
            where l.communityCommentId in :commentIds
            group by l.communityCommentId
            """)
    List<CommunityIdCount> countByCommentIds(@Param("commentIds") Collection<Long> commentIds);

    @Query(
            """
            select l.communityCommentId from CommunityCommentLikeEntity l
            where l.customerId = :customerId and l.communityCommentId in :commentIds
            """)
    List<Long> findLikedCommentIds(
            @Param("customerId") Long customerId, @Param("commentIds") Collection<Long> commentIds);

    @Modifying
    @Query(
            "delete from CommunityCommentLikeEntity l where l.communityCommentId = :commentId"
                    + " and l.customerId = :customerId")
    int deleteLike(@Param("commentId") Long commentId, @Param("customerId") Long customerId);
}
