package com.app.maria.domain.community.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CommunityCommentLikeId implements Serializable {
    private Long communityCommentId;
    private Long customerId;
}
