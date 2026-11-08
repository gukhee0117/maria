package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 끼루 답변의 DB 작업만 짧은 트랜잭션으로 맡는다. 답을 만드는 동안(외부 호출)에는 DB 잠금을 잡지 않고, 저장할 때만 호출한 글·댓글 행을 잠가 상태를 다시 확인한다.
 */
@Component
@RequiredArgsConstructor
public class KkiruReplyWriter {
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final KkiruReplyComposer composer;
    private final BusinessClockService clock;

    /** 아직 PENDING인 호출만 질문으로 돌려준다. 이미 끝났거나 없으면 null. */
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public KkiruQuestion loadPendingQuestion(Long postId, Long commentId) {
        Optional<CommunityPostEntity> post = postRepository.findById(postId);
        if (post.isEmpty()) {
            return null;
        }
        if (commentId == null) {
            return post.get().getKkiruStatus() == KkiruStatus.PENDING
                    ? new KkiruQuestion(
                            postId, null, post.get().getTicker(), post.get().getContent())
                    : null;
        }
        return commentRepository
                .findById(commentId)
                .filter(c -> c.getKkiruStatus() == KkiruStatus.PENDING)
                .map(
                        c ->
                                new KkiruQuestion(
                                        postId, commentId, post.get().getTicker(), c.getContent()))
                .orElse(null);
    }

    /** 검증을 통과한 답으로 답글을 저장하고 호출 상태를 ANSWERED 또는 FAILED로 끝낸다. 끝난 상태를 돌려주고, 이미 끝난 호출이면 null. */
    @Transactional(transactionManager = "jpaTransactionManager")
    public KkiruStatus finish(Long postId, Long commentId, KkiruAnswer answer) {
        Caller caller = lockCaller(postId, commentId);
        if (caller == null || caller.status() != KkiruStatus.PENDING) {
            return null; // 그 사이 다른 처리가 끝냈다
        }
        if (!caller.visible() || !postVisible(postId)) {
            return end(caller, KkiruStatus.FAILED); // 호출한 글·댓글이나 그 글이 숨겨지거나 지워졌다: 답글을 달지 않는다
        }
        Optional<KkiruReplyComposer.Composed> composed = composer.compose(answer);
        if (composed.isEmpty()) {
            return end(caller, KkiruStatus.FAILED);
        }
        if (!answerExists(postId, commentId)) {
            commentRepository.save(
                    CommunityCommentEntity.ofAi(
                            postId,
                            commentId,
                            composed.get().content(),
                            composed.get().sourcesJson(),
                            clock.now()));
        }
        return end(caller, KkiruStatus.ANSWERED);
    }

    /** 근거를 못 찾았을 때 PENDING을 NO_SOURCE로 끝낸다. 이미 끝난 호출은 건드리지 않는다. */
    @Transactional(transactionManager = "jpaTransactionManager")
    public void markNoSource(Long postId, Long commentId) {
        endPending(postId, commentId, KkiruStatus.NO_SOURCE);
    }

    /** 오류로 답을 못 만들었을 때 PENDING을 FAILED로 끝낸다. 이미 끝난 호출은 건드리지 않는다. */
    @Transactional(transactionManager = "jpaTransactionManager")
    public void markFailed(Long postId, Long commentId) {
        endPending(postId, commentId, KkiruStatus.FAILED);
    }

    /** 커밋 뒤 콜백처럼 이미 끝난 트랜잭션 안에서 불릴 때 쓰는 FAILED 처리. 기존 트랜잭션에 참여하면 저장되지 않으므로 반드시 새 트랜잭션으로 한다. */
    @Transactional(
            transactionManager = "jpaTransactionManager",
            propagation = Propagation.REQUIRES_NEW)
    public void markFailedInNewTransaction(Long postId, Long commentId) {
        endPending(postId, commentId, KkiruStatus.FAILED);
    }

    private void endPending(Long postId, Long commentId, KkiruStatus status) {
        Caller caller = lockCaller(postId, commentId);
        if (caller != null && caller.status() == KkiruStatus.PENDING) {
            end(caller, status);
        }
    }

    private static KkiruStatus end(Caller caller, KkiruStatus status) {
        caller.moveTo(status);
        return status;
    }

    private boolean postVisible(Long postId) {
        return postRepository.findById(postId).map(CommunityPostEntity::isVisible).orElse(false);
    }

    private boolean answerExists(Long postId, Long commentId) {
        return commentId == null
                ? commentRepository.existsByCommunityPostIdAndParentCommentIdIsNullAndAuthorType(
                        postId, AuthorType.AI)
                : commentRepository.existsByCommunityPostIdAndParentCommentIdAndAuthorType(
                        postId, commentId, AuthorType.AI);
    }

    private Caller lockCaller(Long postId, Long commentId) {
        if (commentId == null) {
            return postRepository.findByIdForUpdate(postId).map(PostCaller::new).orElse(null);
        }
        return commentRepository.findByIdForUpdate(commentId).map(CommentCaller::new).orElse(null);
    }

    private interface Caller {
        KkiruStatus status();

        boolean visible();

        void moveTo(KkiruStatus next);
    }

    private record PostCaller(CommunityPostEntity post) implements Caller {
        public KkiruStatus status() {
            return post.getKkiruStatus();
        }

        public boolean visible() {
            return post.getStatus() == CommunityStatus.VISIBLE;
        }

        public void moveTo(KkiruStatus next) {
            post.moveKkiruStatus(next);
        }
    }

    private record CommentCaller(CommunityCommentEntity comment) implements Caller {
        public KkiruStatus status() {
            return comment.getKkiruStatus();
        }

        public boolean visible() {
            return comment.getStatus() == CommunityStatus.VISIBLE;
        }

        public void moveTo(KkiruStatus next) {
            comment.moveKkiruStatus(next);
        }
    }
}
