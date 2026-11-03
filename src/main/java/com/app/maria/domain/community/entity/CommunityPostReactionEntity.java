package com.app.maria.domain.community.entity;

import com.app.maria.domain.community.type.ReactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "community_post_reaction")
@IdClass(CommunityPostReactionId.class)
public class CommunityPostReactionEntity {
    @Id
    @Column(name = "community_post_id")
    private Long communityPostId;

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "reaction_type", length = 10)
    private ReactionType reactionType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static CommunityPostReactionEntity of(
            Long postId, Long customerId, ReactionType type, LocalDateTime now) {
        CommunityPostReactionEntity reaction = new CommunityPostReactionEntity();
        reaction.communityPostId = postId;
        reaction.customerId = customerId;
        reaction.reactionType = type;
        reaction.createdAt = now;
        return reaction;
    }
}
