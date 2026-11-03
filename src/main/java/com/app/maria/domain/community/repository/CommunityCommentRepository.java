package com.app.maria.domain.community.repository;

import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    /** 글당 한도 계산용: 그 글에서 한도에 세는 상태로 끼루를 부른 댓글 수. */
    long countByCommunityPostIdAndKkiruStatusIn(Long postId, Collection<KkiruStatus> statuses);

    /** 하루 한도 계산용: 사용자가 한도에 세는 상태로 끼루를 부른 댓글 수. */
    long countByCustomerIdAndKkiruStatusInAndCreatedAtGreaterThanEqual(
            Long customerId, Collection<KkiruStatus> statuses, LocalDateTime since);

    /** 화제 집계용: 기간 안에 그 종목 글에 달린 보이는 이용자 댓글 수(끼루 댓글 제외), 종목별. */
    @Query(
            """
            select p.ticker as ticker, count(c) as commentCount
            from CommunityCommentEntity c
                join CommunityPostEntity p on p.communityPostId = c.communityPostId
            where p.ticker is not null
                and p.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
                and c.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
                and c.authorType = com.app.maria.domain.community.type.AuthorType.CUSTOMER
                and c.createdAt >= :from and c.createdAt < :to
            group by p.ticker
            """)
    List<CommunityTickerCommentCount> countTickerComments(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 한 줄 이유 입력용: 기간 안에 그 종목 글에 달린 보이는 이용자 댓글, 최신순. */
    @Query(
            """
            select c from CommunityCommentEntity c
            where c.communityPostId in (
                    select p.communityPostId from CommunityPostEntity p
                    where p.ticker = :ticker
                        and p.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE)
                and c.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
                and c.authorType = com.app.maria.domain.community.type.AuthorType.CUSTOMER
                and c.createdAt >= :from and c.createdAt < :to
            order by c.communityCommentId desc
            """)
    List<CommunityCommentEntity> findRecentTickerComments(
            @Param("ticker") String ticker,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /** 전체 하루 상한 계산용: 그 시각 이후 만들어진 댓글 중 해당 상태인 것의 수. */
    long countByKkiruStatusInAndCreatedAtGreaterThanEqual(
            Collection<KkiruStatus> statuses, LocalDateTime since);

    /** 오래된 PENDING 정리: cutoff 이전에 만들어졌고 보호 목록에 없는 PENDING을 FAILED로. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update CommunityCommentEntity c
            set c.kkiruStatus = com.app.maria.domain.community.type.KkiruStatus.FAILED
            where c.kkiruStatus = com.app.maria.domain.community.type.KkiruStatus.PENDING
                and c.createdAt < :cutoff
                and c.communityCommentId not in :protectedIds
            """)
    int failStalePending(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("protectedIds") Collection<Long> protectedIds);
}
