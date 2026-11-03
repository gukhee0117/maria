package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.global.exception.AppException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 주장 확인 도구(설계 후속 문서 5절). 확인할 주장을 찾고, 주장 글을 질문으로 삼아 기존 공식 자료 도구(배당·자사주·증자·재무·공시·위험) 중 단어가 맞는 것을 돌려,
 * 주장(이용자 글 근거)과 공식 자료 근거를 함께 돌려준다. 비교와 서술은 LLM이 하고(프롬프트의 주장 확인 규칙), 코드는 숫자·표현을 검증한다. 공식 자료 근거가 하나도
 * 없으면 빈 결과다("확인된 자료가 없어요").
 *
 * <p>주장 찾기: 댓글로 불렀고 다른 댓글에 답한 것이면 그 댓글, 아니면 그 글 본문. 글 본문으로 불렀으면 그 글 자신의 본문(질문과 주장이 같은 글에 있다). 끼루
 * 댓글·숨김·삭제된 글이 대상이면 확인하지 않는다.
 */
@Slf4j
@Component
@Order(80)
@RequiredArgsConstructor
public class ClaimCheckTool implements EvidenceTool {
    private static final List<String> WORDS =
            List.of("사실", "팩트", "진짜", "맞아", "맞나", "맞는지", "거짓", "루머", "소문", "확인해", "믿어도");
    static final int MAX_OFFICIAL_TOOLS = 3;
    // 도구를 고를 때는 주장 전체를, 근거로 넣을 때는 앞부분을 쓴다.
    private static final int SELECTION_LENGTH = 500;
    static final int CLAIM_LENGTH = 300;

    private record Claim(String id, String text) {}

    // 도구 목록이 이 도구 자신을 포함하므로 지연 조회한다.
    private final ObjectProvider<EvidenceTool> tools;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;

    @Override
    public String name() {
        return "claim";
    }

    @Override
    public boolean isRelevant(KkiruQuestion question) {
        return QuestionWords.containsAny(question, WORDS);
    }

    @Override
    public List<Evidence> collect(KkiruQuestion question) {
        Optional<Claim> claim = findClaim(question);
        if (claim.isEmpty()) {
            return List.of();
        }
        KkiruQuestion claimQuestion =
                new KkiruQuestion(
                        question.postId(),
                        question.commentId(),
                        question.ticker(),
                        UserText.clean(claim.get().text(), SELECTION_LENGTH));
        List<Evidence> official = officialEvidence(claimQuestion);
        if (official.isEmpty()) {
            return List.of();
        }
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(
                new Evidence(
                        claim.get().id(),
                        UserText.clean(claim.get().text(), CLAIM_LENGTH),
                        null,
                        true));
        evidence.addAll(official);
        return evidence;
    }

    /** 확인할 주장. 확인할 수 없는 대상이면 empty. */
    private Optional<Claim> findClaim(KkiruQuestion question) {
        if (question.commentId() == null) {
            return Optional.of(new Claim("claim:post:" + question.postId(), question.text()));
        }
        Optional<CommunityCommentEntity> caller = commentRepository.findById(question.commentId());
        if (caller.isEmpty()) {
            return Optional.empty();
        }
        Long parentId = caller.get().getParentCommentId();
        if (parentId != null) {
            return commentRepository
                    .findById(parentId)
                    .filter(CommunityCommentEntity::isVisible)
                    .filter(c -> c.getAuthorType() == AuthorType.CUSTOMER)
                    .map(
                            c ->
                                    new Claim(
                                            "claim:comment:" + c.getCommunityCommentId(),
                                            c.getContent()));
        }
        return postRepository
                .findById(question.postId())
                .filter(CommunityPostEntity::isVisible)
                .map(p -> new Claim("claim:post:" + p.getCommunityPostId(), p.getContent()));
    }

    /** 주장 글에 단어가 맞는 공식 자료 도구(최대 3개)를 돌린다. 일부가 실패해도 나머지로 가고, 전부 실패하면 첫 오류를 던진다. */
    private List<Evidence> officialEvidence(KkiruQuestion claimQuestion) {
        List<EvidenceTool> selected =
                tools.orderedStream()
                        .filter(t -> !(t instanceof ClaimCheckTool))
                        .filter(EvidenceTool::needsTicker) // 토론 요약처럼 종목이 필요 없는 도구는 공식 자료 도구가 아니다
                        .filter(t -> t.isRelevant(claimQuestion))
                        .limit(MAX_OFFICIAL_TOOLS)
                        .toList();
        List<Evidence> evidence = new ArrayList<>();
        AppException firstFailure = null;
        int failed = 0;
        for (EvidenceTool tool : selected) {
            try {
                evidence.addAll(tool.collect(claimQuestion));
            } catch (AppException e) {
                failed++;
                firstFailure = firstFailure == null ? e : firstFailure;
                log.warn("[KKIRU_CLAIM_TOOL_FAILED] tool={} {}", tool.name(), e.getErrorType());
            }
        }
        if (!selected.isEmpty() && failed == selected.size()) {
            throw firstFailure;
        }
        return evidence;
    }
}
