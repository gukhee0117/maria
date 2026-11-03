package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.response.AdminReportProcessResponseDTO;
import com.app.maria.domain.community.dto.response.AdminReportResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.entity.CommunityReportEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityIdCount;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.repository.CommunityReportRepository;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.ReportAction;
import com.app.maria.domain.community.type.ReportStatus;
import com.app.maria.domain.customer.repository.CustomerCommunityProfile;
import com.app.maria.domain.customer.repository.CustomerRepository;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 신고 처리. 숨기거나 삭제하면 신고를 인정하고, 되살리면 신고를 기각한다. 처리마다 감사로그를 남긴다. */
@Service
@RequiredArgsConstructor
public class CommunityReportAdminServiceImpl implements CommunityReportAdminService {
    static final String POST = "POST";
    static final String COMMENT = "COMMENT";

    private final CommunityReportRepository reportRepository;
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CustomerRepository customerRepository;
    private final AuditLogService auditLogService;
    private final AuditActorProvider auditActorProvider;
    private final BusinessClockService clock;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager", readOnly = true)
    public List<AdminReportResponseDTO> list(ReportStatus status, int size) {
        List<CommunityReportEntity> reports =
                reportRepository.findByStatusOrderByCommunityReportIdDesc(
                        status, PageRequest.of(0, size));
        if (reports.isEmpty()) {
            return List.of();
        }
        Set<Long> postIds = new HashSet<>();
        Set<Long> commentIds = new HashSet<>();
        for (CommunityReportEntity report : reports) {
            if (report.getCommunityPostId() != null) {
                postIds.add(report.getCommunityPostId());
            } else {
                commentIds.add(report.getCommunityCommentId());
            }
        }
        Map<Long, CommunityPostEntity> posts = new HashMap<>();
        postRepository.findAllById(postIds).forEach(p -> posts.put(p.getCommunityPostId(), p));
        Map<Long, CommunityCommentEntity> comments = new HashMap<>();
        commentRepository
                .findAllById(commentIds)
                .forEach(c -> comments.put(c.getCommunityCommentId(), c));
        Map<Long, Long> pendingOnPosts =
                postIds.isEmpty()
                        ? Map.of()
                        : toMap(reportRepository.countPendingByPostIds(postIds));
        Map<Long, Long> pendingOnComments =
                commentIds.isEmpty()
                        ? Map.of()
                        : toMap(reportRepository.countPendingByCommentIds(commentIds));

        Set<Long> authorIds = new HashSet<>();
        posts.values().forEach(p -> authorIds.add(p.getCustomerId()));
        comments.values()
                .forEach(
                        c -> {
                            if (c.getCustomerId() != null) {
                                authorIds.add(c.getCustomerId());
                            }
                        });
        Map<Long, String> nicknames = new HashMap<>();
        if (!authorIds.isEmpty()) {
            for (CustomerCommunityProfile profile :
                    customerRepository.findCommunityProfiles(authorIds)) {
                nicknames.put(profile.getCustomerId(), profile.getNickname());
            }
        }

        return reports.stream()
                .map(
                        report -> {
                            if (report.getCommunityPostId() != null) {
                                CommunityPostEntity post = posts.get(report.getCommunityPostId());
                                return response(
                                        report,
                                        POST,
                                        post.getCommunityPostId(),
                                        post.getCommunityPostId(),
                                        pendingOnPosts.getOrDefault(post.getCommunityPostId(), 0L),
                                        post.getStatus(),
                                        post.getContent(),
                                        post.getCustomerId(),
                                        nicknames);
                            }
                            CommunityCommentEntity comment =
                                    comments.get(report.getCommunityCommentId());
                            return response(
                                    report,
                                    COMMENT,
                                    comment.getCommunityCommentId(),
                                    comment.getCommunityPostId(),
                                    pendingOnComments.getOrDefault(
                                            comment.getCommunityCommentId(), 0L),
                                    comment.getStatus(),
                                    comment.getContent(),
                                    comment.getCustomerId(),
                                    nicknames);
                        })
                .toList();
    }

    /**
     * 신고 처리. READ_COMMITTED인 이유: REPEATABLE READ면 첫 SELECT의 snapshot으로 잠금을 기다린 뒤에도 앞 관리자가 처리한 신고를
     * PENDING으로 읽어 같은 대상을 두 번 처리한다. 대상을 잠근 뒤 처리 대기 신고를 다시 읽어, 이 신고가 아직 대기가 아니면 이미 처리된 것으로 본다.
     */
    @Override
    @Transactional(
            transactionManager = "jpaTransactionManager",
            isolation = Isolation.READ_COMMITTED)
    public AdminReportProcessResponseDTO process(Long reportId, ReportAction action) {
        Long adminId = auditActorProvider.getCurrentAdminId();
        CommunityReportEntity report =
                reportRepository
                        .findById(reportId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.COMMUNITY_REPORT_NOT_FOUND, reportId));
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_ALREADY_PROCESSED, reportId);
        }
        boolean accept = action != ReportAction.RESTORE;
        List<CommunityReportEntity> sameTarget;
        String targetType;
        Long targetId;
        CommunityStatus before;
        CommunityStatus after;
        if (report.getCommunityPostId() != null) {
            targetType = POST;
            targetId = report.getCommunityPostId();
            CommunityPostEntity post =
                    postRepository
                            .findByIdForUpdate(targetId)
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorType.COMMUNITY_POST_NOT_FOUND, targetId));
            sameTarget =
                    reportRepository.findByCommunityPostIdAndStatus(targetId, ReportStatus.PENDING);
            requireStillPending(sameTarget, reportId);
            before = post.getStatus();
            apply(action, post::hide, post::markDeleted, post::restore, before, targetId);
            after = post.getStatus();
        } else {
            targetType = COMMENT;
            targetId = report.getCommunityCommentId();
            CommunityCommentEntity comment =
                    commentRepository
                            .findByIdForUpdate(targetId)
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorType.COMMUNITY_COMMENT_NOT_FOUND,
                                                    targetId));
            sameTarget =
                    reportRepository.findByCommunityCommentIdAndStatus(
                            targetId, ReportStatus.PENDING);
            requireStillPending(sameTarget, reportId);
            before = comment.getStatus();
            apply(action, comment::hide, comment::markDeleted, comment::restore, before, targetId);
            after = comment.getStatus();
        }
        for (CommunityReportEntity pending : sameTarget) {
            if (accept) {
                pending.accept(adminId, clock.now());
            } else {
                pending.reject(adminId, clock.now());
            }
        }
        auditLogService.log(
                AuditLogDTO.builder()
                        .adminId(adminId)
                        .targetTable(
                                POST.equals(targetType) ? "community_post" : "community_comment")
                        .targetPk(String.valueOf(targetId))
                        .beforeValue(before.name())
                        .afterValue(after.name())
                        .reasonCode("COMMUNITY_REPORT_" + action.name())
                        .processedAt(clock.now())
                        .build());
        return new AdminReportProcessResponseDTO(targetType, targetId, after, sameTarget.size());
    }

    /** 대상을 잠근 뒤 다시 읽은 처리 대기 신고에 이 신고가 없으면, 잠금을 기다리는 사이 다른 관리자가 먼저 처리한 것이다. */
    private static void requireStillPending(List<CommunityReportEntity> pending, Long reportId) {
        if (pending.stream().noneMatch(r -> r.getCommunityReportId().equals(reportId))) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_ALREADY_PROCESSED, reportId);
        }
    }

    /** 삭제된 대상은 어떤 처리도 할 수 없다. 이미 보이는 대상을 되살리는 것은 변경 없이 신고만 기각한다. */
    private static void apply(
            ReportAction action,
            Runnable hide,
            Runnable delete,
            Runnable restore,
            CommunityStatus current,
            Long targetId) {
        if (current == CommunityStatus.DELETED) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE, targetId);
        }
        switch (action) {
            case HIDE -> hide.run();
            case DELETE -> delete.run();
            case RESTORE -> {
                if (current == CommunityStatus.HIDDEN) {
                    restore.run();
                }
            }
        }
    }

    private static AdminReportResponseDTO response(
            CommunityReportEntity report,
            String targetType,
            Long targetId,
            Long postId,
            long pendingCount,
            CommunityStatus targetStatus,
            String content,
            Long authorId,
            Map<Long, String> nicknames) {
        return new AdminReportResponseDTO(
                report.getCommunityReportId(),
                targetType,
                targetId,
                postId,
                report.getReason(),
                report.getStatus(),
                pendingCount,
                targetStatus,
                content,
                authorId,
                authorId == null ? null : nicknames.get(authorId),
                report.getCreatedAt());
    }

    private static Map<Long, Long> toMap(Collection<CommunityIdCount> counts) {
        Map<Long, Long> map = new HashMap<>();
        counts.forEach(c -> map.put(c.getId(), c.getTotal()));
        return map;
    }
}
