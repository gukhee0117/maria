package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.CommentResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentLikeRepository;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityIdCount;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.customer.repository.CustomerCommunityProfile;
import com.app.maria.domain.customer.repository.CustomerRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 댓글 엔티티 여러 개를 화면용 응답으로 바꾼다. 작성자·좋아요 수·답하는 대상은 한 번에 읽는다. */
@Component
@RequiredArgsConstructor
public class CommunityCommentAssembler {
    public static final String AI_NICKNAME = "끼루 AI";
    public static final String HIDDEN_TEXT = "신고로 가려진 댓글입니다";
    private static final String UNKNOWN_AUTHOR = "알 수 없음";

    private final CommunityCommentLikeRepository likeRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityPostRepository postRepository;
    private final CustomerRepository customerRepository;

    public List<CommentResponseDTO> assemble(List<CommunityCommentEntity> comments, Long viewerId) {
        if (comments.isEmpty()) {
            return List.of();
        }
        List<Long> ids =
                comments.stream().map(CommunityCommentEntity::getCommunityCommentId).toList();
        Map<Long, Long> likes = toMap(likeRepository.countByCommentIds(ids));
        Set<Long> liked = new HashSet<>(likeRepository.findLikedCommentIds(viewerId, ids));

        // 답하는 대상 댓글(삭제된 것 포함)과 글 작성자를 한 번에 읽는다.
        Set<Long> parentIds =
                comments.stream()
                        .map(CommunityCommentEntity::getParentCommentId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Map<Long, CommunityCommentEntity> parents = new HashMap<>();
        commentRepository
                .findAllById(parentIds)
                .forEach(p -> parents.put(p.getCommunityCommentId(), p));
        Set<Long> postIds =
                comments.stream()
                        .map(CommunityCommentEntity::getCommunityPostId)
                        .collect(Collectors.toSet());
        Map<Long, Long> postAuthors = new HashMap<>();
        for (CommunityPostEntity post : postRepository.findAllById(postIds)) {
            postAuthors.put(post.getCommunityPostId(), post.getCustomerId());
        }

        Set<Long> customerIds = new HashSet<>();
        comments.forEach(c -> addIfPresent(customerIds, c.getCustomerId()));
        parents.values().forEach(p -> addIfPresent(customerIds, p.getCustomerId()));
        Map<Long, CustomerCommunityProfile> profiles = new HashMap<>();
        if (!customerIds.isEmpty()) {
            customerRepository
                    .findCommunityProfiles(customerIds)
                    .forEach(profile -> profiles.put(profile.getCustomerId(), profile));
        }

        return comments.stream()
                .map(
                        c -> {
                            boolean ai = c.getAuthorType() == AuthorType.AI;
                            CustomerCommunityProfile author =
                                    ai ? null : profiles.get(c.getCustomerId());
                            Long id = c.getCommunityCommentId();
                            return new CommentResponseDTO(
                                    id,
                                    c.getCommunityPostId(),
                                    c.getAuthorType(),
                                    ai ? AI_NICKNAME : nickname(author),
                                    ai ? null : publicInvestorType(author),
                                    !ai && c.getCustomerId().equals(viewerId),
                                    !ai
                                            && c.getCustomerId()
                                                    .equals(
                                                            postAuthors.get(
                                                                    c.getCommunityPostId())),
                                    c.getStatus() == CommunityStatus.HIDDEN
                                            ? HIDDEN_TEXT
                                            : c.getContent(),
                                    c.getStatus(),
                                    likes.getOrDefault(id, 0L),
                                    liked.contains(id),
                                    c.getParentCommentId(),
                                    replyToNickname(parents.get(c.getParentCommentId()), profiles),
                                    c.getKkiruStatus(),
                                    c.getStatus() == CommunityStatus.VISIBLE
                                            ? c.getSourcesJson()
                                            : null,
                                    c.getCreatedAt());
                        })
                .toList();
    }

    private static String replyToNickname(
            CommunityCommentEntity parent, Map<Long, CustomerCommunityProfile> profiles) {
        if (parent == null || parent.getStatus() == CommunityStatus.DELETED) {
            return null;
        }
        if (parent.getAuthorType() == AuthorType.AI) {
            return AI_NICKNAME;
        }
        return nickname(profiles.get(parent.getCustomerId()));
    }

    private static String nickname(CustomerCommunityProfile profile) {
        return profile != null && profile.getNickname() != null
                ? profile.getNickname()
                : UNKNOWN_AUTHOR;
    }

    private static String publicInvestorType(CustomerCommunityProfile profile) {
        if (profile == null
                || !profile.getInvestorTypePublic()
                || profile.getInvestorType() == InvestorType.UNASSIGNED) {
            return null;
        }
        return profile.getInvestorType().name();
    }

    private static void addIfPresent(Set<Long> set, Long value) {
        if (value != null) {
            set.add(value);
        }
    }

    private static Map<Long, Long> toMap(Collection<CommunityIdCount> counts) {
        Map<Long, Long> map = new HashMap<>();
        counts.forEach(c -> map.put(c.getId(), c.getTotal()));
        return map;
    }
}
