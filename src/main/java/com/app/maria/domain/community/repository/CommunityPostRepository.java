package com.app.maria.domain.community.repository;

import com.app.maria.domain.community.entity.CommunityPostEntity;
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

public interface CommunityPostRepository extends JpaRepository<CommunityPostEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CommunityPostEntity p where p.communityPostId = :id")
    Optional<CommunityPostEntity> findByIdForUpdate(@Param("id") Long id);

    @Query(
            """
            select p from CommunityPostEntity p
            where p.communityPostId < :cursor
                and (:allTickers = true
                    or (:general = true and p.ticker is null)
                    or (:general = false and p.ticker = :ticker))
                and (:hasKeyword = false
                    or (p.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
                        and lower(p.content) like :pattern escape '!'))
                and (p.status <> com.app.maria.domain.community.type.CommunityStatus.DELETED
                    or  exists (select 1 from CommunityCommentEntity c
                        where c.communityPostId = p.communityPostId
                           and c.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE))
            order by p.communityPostId desc
            """)
    List<CommunityPostEntity> findFeed(
            @Param("cursor") long cursor,
            @Param("allTickers") boolean allTickers,
            @Param("general") boolean general,
            @Param("ticker") String ticker,
            @Param("hasKeyword") boolean hasKeyword,
            @Param("pattern") String pattern,
            Pageable pageable);

    @Query(
            """
        select p from CommunityPostEntity p
            join CommunityCommentEntity c on c.communityPostId = p.communityPostId
        where p.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
            and c.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
            and p.createdAt >= :since
            and (:allTickers = true
                or (:general = true and p.ticker is null)
                or (:general = false and p.ticker = :ticker))
        group by p
        order by count(c) desc, p.communityPostId desc
        """)
    List<CommunityPostEntity> findPopular(
            @Param("since") LocalDateTime since,
            @Param("allTickers") boolean allTickers,
            @Param("general") boolean general,
            @Param("ticker") String ticker,
            Pageable pageable);

    /** 한 줄 이유 입력용: 기간 안에 그 종목의 보이는 글, 최신순. */
    @Query(
            """
            select p from CommunityPostEntity p
            where p.ticker = :ticker
                and p.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
                and p.createdAt >= :from and p.createdAt < :to
            order by p.communityPostId desc
            """)
    List<CommunityPostEntity> findRecentTickerPosts(
            @Param("ticker") String ticker,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query(
            """
            select p.ticker as ticker, count(p) as postCount
            from CommunityPostEntity p
            where p.ticker is not null
                and p.status = com.app.maria.domain.community.type.CommunityStatus.VISIBLE
                and p.createdAt >= :from and p.createdAt < :to
            group by p.ticker
            order by count(p) desc , p.ticker asc
            """)
    List<CommunityTickerCount> rankTickers(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    @Query(
            """
            select p from CommunityPostEntity p
            where p.customerId = :customerId
                and p.status <> com.app.maria.domain.community.type.CommunityStatus.DELETED
                and p.communityPostId < :cursor
                and (:allTickers = true or p.ticker = :ticker)
            order by p.communityPostId desc
        """)
    List<CommunityPostEntity> findMine(
            @Param("customerId") Long customerId,
            @Param("cursor") long cursor,
            @Param("allTickers") boolean allTickers,
            @Param("ticker") String ticker,
            Pageable pageable);

    long countByCustomerIdAndStatusNot(Long customerId, CommunityStatus status);

    boolean existsByCommunityPostIdAndKkiruStatusIn(Long postId, Collection<KkiruStatus> statuses);

    /** 전체 하루 상한 계산용: 그 시각 이후 만들어진 글 중 해당 상태인 것의 수. */
    long countByKkiruStatusInAndCreatedAtGreaterThanEqual(
            Collection<KkiruStatus> statuses, LocalDateTime since);

    /** 오래된 PENDING 정리: cutoff 이전에 만들어졌고 보호 목록에 없는 PENDING을 FAILED로. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update CommunityPostEntity p
            set p.kkiruStatus = com.app.maria.domain.community.type.KkiruStatus.FAILED
            where p.kkiruStatus = com.app.maria.domain.community.type.KkiruStatus.PENDING
                and p.createdAt < :cutoff
                and p.communityPostId not in :protectedIds
            """)
    int failStalePending(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("protectedIds") Collection<Long> protectedIds);

    long countByCustomerIdAndKkiruStatusInAndCreatedAtGreaterThanEqual(
            Long customerId, Collection<KkiruStatus> statuses, LocalDateTime since);
}
