package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.PostResponseDTO;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityIdCount;
import com.app.maria.domain.community.repository.CommunityPostReactionRepository;
import com.app.maria.domain.customer.entity.InvestorType;
import com.app.maria.domain.customer.repository.CustomerCommunityProfile;
import com.app.maria.domain.customer.repository.CustomerRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 글 엔티티 여러 개를 화면용 응답으로 바꾼다. 작성자·좋아요·댓글 수는 글마다 따로 읽지 않고 한 번에 읽는다. */
@Component
@RequiredArgsConstructor
public class CommunityPostAssembler {
    public static final String HIDDEN_TEXT = "신고로 가려진 글입니다";
    public static final String DELETED_TEXT = "삭제된 글입니다";
    private static final String UNKNOWN_AUTHOR = "알 수 없음";

    private final CommunityPostReactionRepository reactionRepository;
    private final CommunityCommentRepository commentRepository;
    private final CustomerRepository customerRepository;

    public List<PostResponseDTO> assemble(List<CommunityPostEntity> posts, Long viewerId) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = posts.stream().map(CommunityPostEntity::getCommunityPostId).toList();
        Set<Long> authorIds =
                posts.stream().map(CommunityPostEntity::getCustomerId).collect(Collectors.toSet());

        Map<Long, Long> likes = toMap(reactionRepository.countLikesByPostIds(postIds));
        Map<Long, Long> comments = toMap(commentRepository.countVisibleByPostIds(postIds));
        Set<Long> liked = new HashSet<>(reactionRepository.findLikedPostIds(viewerId, postIds));
        Map<Long, CustomerCommunityProfile> authors = new HashMap<>();
        customerRepository
                .findCommunityProfiles(authorIds)
                .forEach(profile -> authors.put(profile.getCustomerId(), profile));

        return posts.stream()
                .map(
                        post -> {
                            Long id = post.getCommunityPostId();
                            CustomerCommunityProfile author = authors.get(post.getCustomerId());
                            boolean mine = post.getCustomerId().equals(viewerId);
                            return new PostResponseDTO(
                                    id,
                                    post.getTicker(),
                                    author != null && author.getNickname() != null
                                            ? author.getNickname()
                                            : UNKNOWN_AUTHOR,
                                    publicInvestorType(author),
                                    mine,
                                    visibleContent(post),
                                    post.getStatus(),
                                    likes.getOrDefault(id, 0L),
                                    comments.getOrDefault(id, 0L),
                                    liked.contains(id),
                                    post.getKkiruStatus(),
                                    post.getEditedAt() != null,
                                    mine && post.canEdit(),
                                    post.getCreatedAt());
                        })
                .toList();
    }

    private static String visibleContent(CommunityPostEntity post) {
        return switch (post.getStatus()) {
            case VISIBLE -> post.getContent();
            case HIDDEN -> HIDDEN_TEXT;
            case DELETED -> DELETED_TEXT;
        };
    }

    /** 작성자가 성향 공개에 동의했고 진단을 마쳤을 때만 성향 이름을 낸다. */
    private static String publicInvestorType(CustomerCommunityProfile author) {
        if (author == null
                || !author.getInvestorTypePublic()
                || author.getInvestorType() == InvestorType.UNASSIGNED) {
            return null;
        }
        return author.getInvestorType().name();
    }

    private static Map<Long, Long> toMap(Collection<CommunityIdCount> counts) {
        Map<Long, Long> map = new HashMap<>();
        counts.forEach(c -> map.put(c.getId(), c.getTotal()));
        return map;
    }
}
