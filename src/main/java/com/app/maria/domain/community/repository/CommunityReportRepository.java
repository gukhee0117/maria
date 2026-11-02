package com.app.maria.domain.community.repository;

import com.app.maria.domain.community.entity.CommunityReportEntity;
import com.app.maria.domain.community.type.ReportStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityReportRepository extends JpaRepository<CommunityReportEntity, Long> {

    boolean existsByReporterIdAndCommunityPostId(Long reporterId, Long postId);

    boolean existsByReporterIdAndCommunityCommentId(Long reporterId, Long commentId);

    long countByCommunityPostIdAndStatus(Long postId, ReportStatus status);

    long countByCommunityCommentIdAndStatus(Long commentId, ReportStatus status);

    List<CommunityReportEntity> findByCommunityPostIdAndStatus(Long postId, ReportStatus status);

    List<CommunityReportEntity> findByCommunityCommentIdAndStatus(
            Long commentId, ReportStatus status);

    /** 글마다 처리 대기 신고 수를 한 번에 센다(관리자 목록용). */
    @Query(
            """
            select r.communityPostId as id, count(r) as total from CommunityReportEntity r
            where r.communityPostId in :postIds
              and r.status = com.app.maria.domain.community.type.ReportStatus.PENDING
            group by r.communityPostId
            """)
    List<CommunityIdCount> countPendingByPostIds(@Param("postIds") Collection<Long> postIds);

    @Query(
            """
            select r.communityCommentId as id, count(r) as total from CommunityReportEntity r
            where r.communityCommentId in :commentIds
              and r.status = com.app.maria.domain.community.type.ReportStatus.PENDING
            group by r.communityCommentId
            """)
    List<CommunityIdCount> countPendingByCommentIds(
            @Param("commentIds") Collection<Long> commentIds);

    List<CommunityReportEntity> findByStatusOrderByCommunityReportIdDesc(
            ReportStatus status, Pageable pageable);
}
