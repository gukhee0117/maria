package com.app.maria.domain.community.entity;

import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "community_comment")
public class CommunityCommentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "community_comment_id")
    private Long communityCommentId;

    @Column(name = "community_post_id", nullable = false)
    private Long communityPostId;

    @Column(name = "parent_comment_id")
    private Long parentCommentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false)
    private AuthorType authorType;

    @Column(name = "customer_id")
    private Long customerId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "sources_json")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String sourcesJson;

    @Column(name = "kiru_status", length = 15)
    @Enumerated(EnumType.STRING)
    private KkiruStatus kkiruStatus;

    @Column(name = "kiru_retry_count", nullable = false)
    private int kkiruRetryCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CommunityStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static CommunityCommentEntity ofCustomer(
            Long postId,
            Long replyToCommentId,
            Long customerId,
            String content,
            KkiruStatus kkiruStatus,
            LocalDateTime now) {
        return CommunityCommentEntity.builder()
                .communityPostId(postId)
                .parentCommentId(replyToCommentId)
                .customerId(customerId)
                .content(CommunityContent.requireValid(content))
                .authorType(AuthorType.CUSTOMER)
                .kkiruStatus(kkiruStatus)
                .status(CommunityStatus.VISIBLE)
                .createdAt(now)
                .build();
    }

    /** 끼루 답글. 호출한 댓글이 있으면 그 id, 글 본문에서 호출했으면 null을 parentCommentId로 준다. */
    public static CommunityCommentEntity ofAi(
            Long postId,
            Long calledCommentId,
            String content,
            String sourcesJson,
            LocalDateTime now) {
        return CommunityCommentEntity.builder()
                .communityPostId(postId)
                .parentCommentId(calledCommentId)
                .authorType(AuthorType.AI)
                .content(CommunityContent.requireValid(content))
                .sourcesJson(sourcesJson)
                .status(CommunityStatus.VISIBLE)
                .createdAt(now)
                .build();
    }

    public boolean isVisible() {
        return status == CommunityStatus.VISIBLE;
    }

    public void hide() {
        if (status == CommunityStatus.DELETED) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        }
        this.status = CommunityStatus.HIDDEN;
    }

    public void restore() {
        if (status != CommunityStatus.HIDDEN) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        }
        this.status = CommunityStatus.VISIBLE;
    }

    public void markDeleted() {
        this.status = CommunityStatus.DELETED;
    }

    public void moveKkiruStatus(KkiruStatus next) {
        if (!KkiruStatus.canMove(kkiruStatus, next)) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_STATUS_INVALID);
        }
        this.kkiruStatus = next;
    }

    /** 다시 묻기: FAILED를 PENDING으로 돌리고 횟수를 센다. 횟수 상한은 호출하는 쪽(KkiruCallPlanner)이 확인한다. */
    public void restartKkiru() {
        moveKkiruStatus(KkiruStatus.PENDING);
        kkiruRetryCount++;
    }
}
