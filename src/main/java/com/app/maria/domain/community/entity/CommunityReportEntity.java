package com.app.maria.domain.community.entity;

import com.app.maria.domain.community.type.ReportReason;
import com.app.maria.domain.community.type.ReportStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "community_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityReportEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "community_report_id")
    private Long communityReportId;

    @Column(name = "community_post_id")
    private Long communityPostId;

    @Column(name = "community_comment_id")
    private Long communityCommentId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private ReportReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ReportStatus status;

    /** 처리한 관리자. 처리 전이면 NULL */
    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static CommunityReportEntity ofPost(
            Long postId, Long reporterId, ReportReason reason, LocalDateTime now) {
        return create(postId, null, reporterId, reason, now);
    }

    public static CommunityReportEntity ofComment(
            Long commentId, Long reporterId, ReportReason reason, LocalDateTime now) {
        return create(null, commentId, reporterId, reason, now);
    }

    private static CommunityReportEntity create(
            Long postId, Long commentId, Long reporterId, ReportReason reason, LocalDateTime now) {
        CommunityReportEntity report = new CommunityReportEntity();
        report.communityPostId = postId;
        report.communityCommentId = commentId;
        report.reporterId = reporterId;
        report.reason = reason;
        report.status = ReportStatus.PENDING;
        report.createdAt = now;
        return report;
    }

    public void accept(Long adminId, LocalDateTime now) {
        process(ReportStatus.ACCEPTED, adminId, now);
    }

    public void reject(Long adminId, LocalDateTime now) {
        process(ReportStatus.REJECTED, adminId, now);
    }

    private void process(ReportStatus result, Long adminId, LocalDateTime now) {
        if (status != ReportStatus.PENDING) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_ALREADY_PROCESSED);
        }
        this.status = result;
        this.processedBy = adminId;
        this.processedAt = now;
    }
}
