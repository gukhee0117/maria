package com.app.maria.domain.community.entity;

import com.app.maria.domain.community.type.ReactionType;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CommunityPostReactionId implements Serializable {
    private Long communityPostId;
    private Long customerId;
    private ReactionType reactionType;
}
