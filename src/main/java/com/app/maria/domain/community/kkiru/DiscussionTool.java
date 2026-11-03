package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.AuthorType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * 토론 요약 도구(설계 후속 문서 4절). 호출한 글의 본문과 이용자 댓글(최대 15개)을 근거로 모은다. 이용자 글은 {@code userContent} 근거라서 LLM에는
 * "그 사람이 그렇게 썼다"는 것으로만 전해지고, 템플릿 폴백에는 들어가지 않는다. 숨김·삭제된 글, 끼루 댓글, 호출한 댓글 자신, 닉네임은 쓰지 않는다. 종목이 없는 일반
 * 글에서도 동작한다.
 */
@Component
@Order(70)
@RequiredArgsConstructor
public class DiscussionTool implements EvidenceTool {
    private static final List<String> WORDS = List.of("요약", "정리", "토론", "분위기", "반응", "댓글", "의견");
    static final int MAX_COMMENTS = 15;
    static final int TEXT_LENGTH = 200;
    // 댓글이 아주 많아도 앞쪽만 읽는다(요약에 필요한 만큼이고 조회 비용을 묶어 둔다).
    private static final int SCAN_LIMIT = 100;

    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;

    @Override
    public String name() {
        return "discussion";
    }

    @Override
    public boolean needsTicker() {
        return false; // 종목이 없는 일반 글의 토론도 요약한다
    }

    @Override
    public boolean isRelevant(KkiruQuestion question) {
        return QuestionWords.containsAny(question, WORDS);
    }

    @Override
    public List<Evidence> collect(KkiruQuestion question) {
        Optional<CommunityPostEntity> post =
                postRepository.findById(question.postId()).filter(CommunityPostEntity::isVisible);
        if (post.isEmpty()) {
            return List.of();
        }
        List<CommunityCommentEntity> comments =
                commentRepository
                        .findThread(question.postId(), 0L, PageRequest.of(0, SCAN_LIMIT))
                        .stream()
                        .filter(CommunityCommentEntity::isVisible)
                        .filter(c -> c.getAuthorType() == AuthorType.CUSTOMER)
                        .filter(c -> !c.getCommunityCommentId().equals(question.commentId()))
                        .toList();

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(count(question.postId(), comments.size()));
        // 글 본문으로 호출했으면 그 본문이 곧 질문이라 근거에 넣지 않는다.
        if (question.commentId() != null) {
            quote("discussion:post:" + question.postId(), post.get().getContent())
                    .ifPresent(evidence::add);
        }
        comments.stream()
                .limit(MAX_COMMENTS)
                .forEach(
                        c ->
                                quote(
                                                "discussion:comment:" + c.getCommunityCommentId(),
                                                c.getContent())
                                        .ifPresent(evidence::add));
        return evidence;
    }

    private static Evidence count(Long postId, int comments) {
        String text =
                comments == 0
                        ? "이 글에는 아직 댓글이 없습니다."
                        : comments >= SCAN_LIMIT
                                ? "이 글에는 댓글 " + SCAN_LIMIT + "개 이상이 달려 있습니다."
                                : "이 글에는 댓글 " + comments + "개가 달려 있습니다.";
        return new Evidence("discussion:count:" + postId, text, null);
    }

    private static Optional<Evidence> quote(String id, String content) {
        String text = UserText.clean(content, TEXT_LENGTH);
        return text.isBlank() ? Optional.empty() : Optional.of(new Evidence(id, text, null, true));
    }
}
