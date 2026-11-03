package com.app.maria.domain.community.service;

import com.app.maria.domain.community.dto.request.ReportRequestDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.entity.CommunityReportEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.repository.CommunityReportRepository;
import com.app.maria.domain.community.type.ReportStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 신고 접수. 같은 대상에 쌓인 처리 대기 신고가 기준(기본 5건) 이상이면 대상을 자동으로 숨긴다. 같은 대상에 대한 동시 신고는 대상 행을 잠가 한 번에 하나씩
 * 처리하므로 중복 확인과 개수 계산이 어긋나지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CommunityReportServiceImpl implements CommunityReportService {
    private final CommunityPostRepository postRepository;
    private final CommunityCommentRepository commentRepository;
    private final CommunityReportRepository reportRepository;
    private final BusinessClockService clock;

    @Value("${maria.community.report.auto-hide-threshold:5}")
    private int autoHideThreshold;

    @Override
    @Transactional(transactionManager = "jpaTransactionManager")
    public void report(Long reporterId, ReportRequestDTO request) {
        boolean onPost = request.postId() != null;
        boolean onComment = request.commentId() != null;
        if (onPost == onComment) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_TARGET_INVALID);
        }
        if (onPost) {
            reportPost(reporterId, request);
        } else {
            reportComment(reporterId, request);
        }
    }

    private void reportPost(Long reporterId, ReportRequestDTO request) {
        Long postId = request.postId();
        CommunityPostEntity post =
                postRepository
                        .findByIdForUpdate(postId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.COMMUNITY_POST_NOT_FOUND, postId));
        if (!post.isVisible()) {
            throw new AppException(ErrorType.COMMUNITY_POST_NOT_VISIBLE, postId);
        }
        if (reportRepository.existsByReporterIdAndCommunityPostId(reporterId, postId)) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_DUPLICATED, postId);
        }
        reportRepository.saveAndFlush(
                CommunityReportEntity.ofPost(postId, reporterId, request.reason(), clock.now()));
        if (reportRepository.countByCommunityPostIdAndStatus(postId, ReportStatus.PENDING)
                >= autoHideThreshold) {
            post.hide();
        }
    }

    private void reportComment(Long reporterId, ReportRequestDTO request) {
        Long commentId = request.commentId();
        CommunityCommentEntity comment =
                commentRepository
                        .findByIdForUpdate(commentId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.COMMUNITY_COMMENT_NOT_FOUND, commentId));
        if (!comment.isVisible()) {
            throw new AppException(ErrorType.COMMUNITY_COMMENT_NOT_FOUND, commentId);
        }
        if (reportRepository.existsByReporterIdAndCommunityCommentId(reporterId, commentId)) {
            throw new AppException(ErrorType.COMMUNITY_REPORT_DUPLICATED, commentId);
        }
        reportRepository.saveAndFlush(
                CommunityReportEntity.ofComment(
                        commentId, reporterId, request.reason(), clock.now()));
        if (reportRepository.countByCommunityCommentIdAndStatus(commentId, ReportStatus.PENDING)
                >= autoHideThreshold) {
            comment.hide();
        }
    }
}
