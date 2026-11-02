package com.app.maria.domain.community.entity;

import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KiruStatus;
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

@Entity
@Builder(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "community_post")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "community_post_id")
    private Long communityPostId;

    @Column(name = "ticker", length = 20)
    private String ticker;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "content", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "kiru_status")
    private KiruStatus kiruStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private CommunityStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    public static CommunityPostEntity write(
            Long customerId,
            String ticker,
            String content,
            KiruStatus kiruStatus,
            LocalDateTime now) {
        return CommunityPostEntity.builder()
                .customerId(customerId)
                .ticker(ticker)
                .content(CommunityContent.requireValid(content))
                .kiruStatus(kiruStatus)
                .status(CommunityStatus.VISIBLE)
                .createdAt(now)
                .editedAt(null)
                .build();
    }

    public boolean isVisible() {
        return status == CommunityStatus.VISIBLE;
    }

    public boolean canEdit() {
        return isVisible() && kiruStatus != KiruStatus.ANSWERED && kiruStatus != KiruStatus.PENDING;
    }

    public void edit(String newContent, LocalDateTime now) {
        if (!canEdit()) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_EDITABLE);
        }
        this.content = CommunityContent.requireValid(newContent);
        this.editedAt = now;
    }

    public void hide() {
        if (status == CommunityStatus.DELETED)
            throw new AppException(ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        this.status = CommunityStatus.HIDDEN;
    }

    public void restore() {
        if (status != CommunityStatus.HIDDEN)
            throw new AppException(ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        this.status = CommunityStatus.VISIBLE;
    }

    public void markDeleted() {
        this.status = CommunityStatus.DELETED;
    }

    public void moveKiruStatus(KiruStatus next) {
        if (!KiruStatus.canMove(kiruStatus, next)) {
            throw new AppException(ErrorType.COMMUNITY_KIRU_STATUS_INVALID);
        }
        this.kiruStatus = next;
    }
}
