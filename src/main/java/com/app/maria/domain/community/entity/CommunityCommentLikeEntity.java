package com.app.maria.domain.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(CommunityCommentLikeId.class)
@Table(name = "community_comment_like")
public class CommunityCommentLikeEntity {
    @Id
    @Column(name = "community_comment_id")
    private Long communityCommentId;

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static CommunityCommentLikeEntity of(
            Long commentId, Long customerId, LocalDateTime now) {
        CommunityCommentLikeEntity like = new CommunityCommentLikeEntity();
        like.communityCommentId = commentId;
        like.customerId = customerId;
        like.createdAt = now;
        return like;
    }
}
