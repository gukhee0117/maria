package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.request.CommentWriteRequestDTO;
import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.CommentResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityContent;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.domain.customer.entity.CustomerEntity;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 글 안의 댓글 쓰기·삭제·목록. 댓글은 한 줄로 쌓이고, 답하는 대상은 parentCommentId에 기록한다. */
@Service
@RequiredArgsConstructor
public class CommunityCommentServiceImpl implements CommunityCommentService {
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CustomerRepository customerRepository;
    private final CommunityCommentAssembler assembler;
    private final CommunityTextGuard textGuard;
    private final KkiruCallPlanner kkiruCallPlanner;
    private final BusinessClockService clock;
    private final ApplicationEventPublisher events;

    /** 댓글을 쓴다. READ_COMMITTED는 끼루 한도 확인용(KkiruCallPlanner 참고). */
    @Override
    @Transactional(
            transactionManager = "jpaTransactionManager",
            isolation = Isolation.READ_COMMITTED)
    public CommentResponseDTO write(Long customerId, Long postId, CommentWriteRequestDTO request) {
        String content = CommunityContent.requireValid(request.content());
        String sanitized = textGuard.requireAllowed(content);

        // 끼루를 부르는 댓글은 글 행을 잠가서 동시에 여러 번 불러 글당 한도를 넘기지 못하게 한다.
        boolean callsKkiru = KkiruMention.in(content);
        CommunityPostEntity post =
                (callsKkiru
                                ? postRepository.findByIdForUpdate(postId)
                                : postRepository.findById(postId))
                        .orElseThrow(
                                () -> new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId));
        if (!post.isVisible()) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_VISIBLE, postId);
        }
        CommunityCommentEntity target = resolveReplyTarget(postId, request.replyToCommentId());

        // 끼루 댓글에 다시 @끼루로 물으면 후속 질문이다: 호출 한도를 쓰지 않고(FOLLOW_UP은 세지 않음) 끼루 챗봇으로 보낸다.
        boolean followUp = callsKkiru && target != null && target.getAuthorType() == AuthorType.AI;
        KkiruStatus kkiruStatus =
                followUp
                        ? KkiruStatus.FOLLOW_UP
                        : kkiruCallPlanner.plan(content, customerId, postId);

        CommunityCommentEntity saved =
                commentRepository.save(
                        CommunityCommentEntity.ofCustomer(
                                postId,
                                target == null ? null : target.getCommunityCommentId(),
                                customerId,
                                sanitized,
                                kkiruStatus,
                                clock.now()));
        if (kkiruStatus == KkiruStatus.PENDING) {
            events.publishEvent(new KkiruCallRequestedEvent(postId, saved.getCommunityCommentId()));
        }
        return assembler.assemble(List.of(saved), customerId).get(0);
    }

    /** 내 댓글의 끼루 호출 다시 묻기. 글 쪽과 같은 규칙에 더해 그 댓글이 달린 글도 보여야 한다. */
    @Override
    @Transactional(
            transactionManager = "jpaTransactionManager",
            isolation = Isolation.READ_COMMITTED)
    public CommentResponseDTO retryKkiru(Long customerId, Long commentId) {
        CommunityCommentEntity comment =
                commentRepository
                        .findByIdForUpdate(commentId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.COMMUNITY_COMMENT_NOT_FOUND, commentId));
        if (comment.getAuthorType() == AuthorType.AI
                || !customerId.equals(comment.getCustomerId())) {
            throw new AppException(ErrorType.COMMUNITY_NOT_AUTHOR, commentId);
        }
        boolean postVisible =
                postRepository
                        .findById(comment.getCommunityPostId())
                        .map(CommunityPostEntity::isVisible)
                        .orElse(false);
        if (comment.getKkiruStatus() != KkiruStatus.FAILED
                || !comment.isVisible()
                || !postVisible) {
            throw new AppException(ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED, commentId);
        }
        kkiruCallPlanner.requireRetryLimits(customerId, comment.getKkiruRetryCount());
        comment.restartKkiru();
        events.publishEvent(new KkiruCallRequestedEvent(comment.getCommunityPostId(), commentId));
        return assembler.assemble(List.of(comment), customerId).get(0);
    }

    /** 본인 댓글 삭제(소프트). 끼루 댓글은 지울 수 없다. */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public void delete(Long customerId, Long commentId) {
        CommunityCommentEntity comment =
                commentRepository
                        .findByIdForUpdate(commentId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.COMMUNITY_COMMENT_NOT_FOUND, commentId));
        if (comment.getStatus() == CommunityStatus.DELETED) {
            throw new AppException(ErrorType.COMMUNITY_COMMENT_NOT_FOUND, commentId);
        }
        if (comment.getAuthorType() == AuthorType.AI
                || !customerId.equals(comment.getCustomerId())) {
            throw new AppException(ErrorType.COMMUNITY_NOT_AUTHOR, commentId);
        }
        // 신고로 숨겨진 댓글은 관리자가 처리할 때까지 작성자가 지울 수 없다.
        if (comment.getStatus() == CommunityStatus.HIDDEN) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE, commentId);
        }
        comment.markDeleted();
    }

    /** 글 안의 댓글을 오래된 순으로. 삭제된 댓글은 빼고, 숨김 댓글은 본문 대신 안내 문구로 보낸다. 숨김 글이면 목록을 주지 않는다. */
    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public CommentPageResponseDTO list(Long viewerId, Long postId, Long cursor, int size) {
        CommunityPostEntity post =
                postRepository
                        .findById(postId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId));
        // 신고로 가려진 글은 댓글로 내용이 드러날 수 있어 목록도 막는다.
        if (post.getStatus() == CommunityStatus.HIDDEN) {
            throw new AppException(ErrorType.COMMUNITY_POST_HIDDEN, postId);
        }
        boolean deletedWithoutComments =
                post.getStatus() == CommunityStatus.DELETED
                        && commentRepository.countByCommunityPostIdAndStatus(
                                        postId, CommunityStatus.VISIBLE)
                                == 0;
        if (deletedWithoutComments) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId);
        }
        List<CommunityCommentEntity> rows =
                commentRepository.findThread(
                        postId, cursor == null ? 0L : cursor, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<CommunityCommentEntity> page = hasMore ? rows.subList(0, size) : rows;
        Long nextCursor = hasMore ? page.get(page.size() - 1).getCommunityCommentId() : null;
        return new CommentPageResponseDTO(assembler.assemble(page, viewerId), nextCursor);
    }

    /**
     * 답할 댓글이 같은 글의 보이는 댓글이고, 쓴 사람의 닉네임이 있는지 확인한다. 닉네임이 없으면 언급할 수 없어 답할 수 없다. 없으면 null(글에 바로 단다).
     */
    private CommunityCommentEntity resolveReplyTarget(Long postId, Long replyToCommentId) {
        if (replyToCommentId == null) {
            return null;
        }
        CommunityCommentEntity target =
                commentRepository
                        .findById(replyToCommentId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.COMMUNITY_REPLY_TARGET_INVALID,
                                                replyToCommentId));
        if (!target.getCommunityPostId().equals(postId) || !target.isVisible()) {
            throw new AppException(ErrorType.COMMUNITY_REPLY_TARGET_INVALID, replyToCommentId);
        }
        // 끼루 댓글은 작성 고객이 없어 검사하지 않는다.
        boolean nicknameless =
                target.getCustomerId() != null
                        && customerRepository
                                .findById(target.getCustomerId())
                                .map(CustomerEntity::getNickname)
                                .isEmpty();
        if (nicknameless) {
            throw new AppException(ErrorType.COMMUNITY_REPLY_TARGET_INVALID, replyToCommentId);
        }
        return target;
    }
}
