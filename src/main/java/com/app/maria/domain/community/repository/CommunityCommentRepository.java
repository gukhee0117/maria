package com.app.maria.domain.community.repository;

import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityCommentRepository extends JpaRepository<CommunityCommentEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CommunityCommentEntity c where c.communityCommentId = :id")
    Optional<CommunityCommentEntity> findByIdForUpdate(@Param("id") Long id);

    @Query(
            """
            select c from CommunityCommentEntity c
            where c.communityPostId = :postId
                and c.communityCommentId > :cursor
                and c.status <> com.app.maria.domain.community.type.CommunityStatus.DELETED
            order by c.communityCommentId asc
        """)
    List<CommunityCommentEntity> findThread(
            @Param("postId") Long postId, @Param("cursor") long cursor, Pageable pageable);

    @Query(
            """
            select c.communityPostId as id, count(c) as total
            from CommunityCommentEntity c
            where c.communityPostId in :postIds
                and c.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
                group by c.communityPostId
""")
    List<CommunityIdCount> countVisibleByPostIds(@Param("postIds") Collection<Long> postIds);

    long countByCommunityPostIdAndStatus(Long postId, CommunityStatus status);

    @Query(
            """
            select c from CommunityCommentEntity c
            where c.customerId = :customerId
                and c.status <> com.app.maria.domain.community.type.CommunityStatus.DELETED
                and c.communityCommentId < :cursor
                and (:allTickers = true
                    or c.communityPostId in (select p.communityPostId from CommunityPostEntity p where p.ticker = :ticker))
            order by c.communityCommentId desc

""")
    List<CommunityCommentEntity> findMine(
            @Param("customerId") Long customerId,
            @Param("cursor") long cursor,
            @Param("allTickers") boolean allTickers,
            @Param("ticker") String ticker,
            Pageable pageable);

    long countByCustomerIdAndStatusNot(Long customerId, CommunityStatus status);

    /** 같은 댓글에 이미 끼루가 답했는지(댓글에서 호출한 경우). */
    boolean existsByCommunityPostIdAndParentCommentIdAndAuthorType(
            Long postId, Long parentCommentId, AuthorType authorType);

    /** 같은 글 본문에 이미 끼루가 답했는지(글 본문에서 호출한 경우, 답글의 parent가 NULL). */
    boolean existsByCommunityPostIdAndParentCommentIdIsNullAndAuthorType(
            Long postId, AuthorType authorType);

    /** 글 하나(그 댓글들)에서 끼루를 부른 횟수. */
    long countByCommunityPostIdAndKiruStatusIsNotNull(Long postId);

    /** 하루 한도 계산용: 사용자가 끼루를 부른 댓글 수. */
    long countByCustomerIdAndKiruStatusIsNotNullAndCreatedAtGreaterThanEqual(
            Long customerId, LocalDateTime since);
}
