package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.response.AdminReportProcessResponseDTO;
import com.app.maria.domain.community.dto.response.AdminReportResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.entity.CommunityReportEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.repository.CommunityReportRepository;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.ReportAction;
import com.app.maria.domain.community.type.ReportReason;
import com.app.maria.domain.community.type.ReportStatus;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({JpaConfig.class, TransactionConfig.class, CommunityReportAdminServiceImpl.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityReportAdminServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);
    private static final long ADMIN = 99L;

    @Autowired private CommunityReportAdminService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private CommunityReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private AuditActorProvider auditActorProvider;
    @MockitoBean private BusinessClockService clock;

    private long author;
    private CommunityPostEntity post;
    private CommunityCommentEntity comment;

    @BeforeAll
    void createSchema() {
        CommunityTestSchema.create(jdbc);
    }

    @AfterAll
    void dropSchema() {
        CommunityTestSchema.drop(jdbc);
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM community_report");
        jdbc.execute("DELETE FROM community_comment");
        jdbc.execute("DELETE FROM community_post");
        jdbc.execute("DELETE FROM customer");
        when(clock.now()).thenReturn(NOW);
        when(auditActorProvider.getCurrentAdminId()).thenReturn(ADMIN);
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash, nickname) VALUES ('홍', '1990-01-01', 'ACTIVE', 'h1', '작성자닉')");
        author = jdbc.queryForObject("SELECT MAX(customer_id) FROM customer", Long.class);
        post =
                postRepository.saveAndFlush(
                        CommunityPostEntity.write(author, "000660", "원문 본문", null, NOW));
        comment =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofCustomer(
                                post.getCommunityPostId(), null, author, "댓글 원문", null, NOW));
    }

    private CommunityReportEntity reportOnPost(long reporter) {
        return reportRepository.saveAndFlush(
                CommunityReportEntity.ofPost(
                        post.getCommunityPostId(), reporter, ReportReason.SPAM, NOW));
    }

    private CommunityReportEntity reportOnComment(long reporter) {
        return reportRepository.saveAndFlush(
                CommunityReportEntity.ofComment(
                        comment.getCommunityCommentId(), reporter, ReportReason.ABUSE, NOW));
    }

    private static void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private ReportStatus reportStatus(CommunityReportEntity report) {
        entityManager.flush();
        entityManager.clear();
        return reportRepository.findById(report.getCommunityReportId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("목록에는 숨겨진 글의 원문, 작성자, 같은 대상의 대기 신고 수가 들어간다")
    void listShowsOriginalContent() {
        reportOnPost(10);
        reportOnPost(11);
        reportOnComment(10);
        post.hide();
        postRepository.flush();

        List<AdminReportResponseDTO> list = service.list(ReportStatus.PENDING, 10);

        assertThat(list).hasSize(3);
        AdminReportResponseDTO commentReport = list.get(0);
        assertThat(commentReport.targetType()).isEqualTo("COMMENT");
        assertThat(commentReport.targetContent()).isEqualTo("댓글 원문");
        assertThat(commentReport.postId()).isEqualTo(post.getCommunityPostId());
        assertThat(commentReport.pendingReportCount()).isEqualTo(1);
        AdminReportResponseDTO postReport = list.get(1);
        assertThat(postReport.targetType()).isEqualTo("POST");
        assertThat(postReport.targetContent()).isEqualTo("원문 본문");
        assertThat(postReport.targetStatus()).isEqualTo(CommunityStatus.HIDDEN);
        assertThat(postReport.pendingReportCount()).isEqualTo(2);
        assertThat(postReport.authorNickname()).isEqualTo("작성자닉");
        assertThat(postReport.authorCustomerId()).isEqualTo(author);
    }

    @Test
    @DisplayName("목록은 상태로 거르고 최신 신고가 먼저이며 개수를 제한한다")
    void listFiltersAndLimits() {
        CommunityReportEntity r1 = reportOnPost(10);
        reportOnPost(11);
        r1.accept(ADMIN, NOW);
        reportRepository.flush();

        assertThat(service.list(ReportStatus.PENDING, 10)).hasSize(1);
        assertThat(service.list(ReportStatus.ACCEPTED, 10)).hasSize(1);
        assertThat(service.list(ReportStatus.REJECTED, 10)).isEmpty();
        assertThat(service.list(ReportStatus.PENDING, 1)).hasSize(1);
    }

    @Test
    @DisplayName("HIDE는 글을 숨기고 같은 글의 대기 신고를 모두 인정하며 감사로그를 남긴다")
    void hide() {
        CommunityReportEntity first = reportOnPost(10);
        CommunityReportEntity second = reportOnPost(11);
        CommunityReportEntity onComment = reportOnComment(10);

        AdminReportProcessResponseDTO result =
                service.process(first.getCommunityReportId(), ReportAction.HIDE);

        assertThat(result.targetStatus()).isEqualTo(CommunityStatus.HIDDEN);
        assertThat(result.processedReportCount()).isEqualTo(2);
        assertThat(reportStatus(first)).isEqualTo(ReportStatus.ACCEPTED);
        assertThat(reportStatus(second)).isEqualTo(ReportStatus.ACCEPTED);
        assertThat(reportStatus(onComment)).isEqualTo(ReportStatus.PENDING); // 다른 대상은 건드리지 않는다
        CommunityReportEntity processed =
                reportRepository.findById(first.getCommunityReportId()).orElseThrow();
        assertThat(processed.getProcessedBy()).isEqualTo(ADMIN);
        assertThat(processed.getProcessedAt()).isEqualTo(NOW);
        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(captor.capture());
        AuditLogDTO log = captor.getValue();
        assertThat(log.getAdminId()).isEqualTo(ADMIN);
        assertThat(log.getTargetTable()).isEqualTo("community_post");
        assertThat(log.getTargetPk()).isEqualTo(String.valueOf(post.getCommunityPostId()));
        assertThat(log.getBeforeValue()).isEqualTo("VISIBLE");
        assertThat(log.getAfterValue()).isEqualTo("HIDDEN");
        assertThat(log.getReasonCode()).isEqualTo("COMMUNITY_REPORT_HIDE");
    }

    @Test
    @DisplayName("DELETE는 대상을 삭제 상태로 만들고 신고를 인정한다(댓글)")
    void deleteComment() {
        CommunityReportEntity report = reportOnComment(10);

        AdminReportProcessResponseDTO result =
                service.process(report.getCommunityReportId(), ReportAction.DELETE);

        assertThat(result.targetType()).isEqualTo("COMMENT");
        assertThat(result.targetStatus()).isEqualTo(CommunityStatus.DELETED);
        assertThat(reportStatus(report)).isEqualTo(ReportStatus.ACCEPTED);
        ArgumentCaptor<AuditLogDTO> captor = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(captor.capture());
        assertThat(captor.getValue().getTargetTable()).isEqualTo("community_comment");
        assertThat(captor.getValue().getReasonCode()).isEqualTo("COMMUNITY_REPORT_DELETE");
    }

    @Test
    @DisplayName("RESTORE는 숨김을 되살리고 신고를 기각한다. 이미 보이는 대상이면 변경 없이 기각만 한다")
    void restore() {
        CommunityReportEntity report = reportOnPost(10);
        post.hide();
        postRepository.flush();

        AdminReportProcessResponseDTO result =
                service.process(report.getCommunityReportId(), ReportAction.RESTORE);

        assertThat(result.targetStatus()).isEqualTo(CommunityStatus.VISIBLE);
        assertThat(reportStatus(report)).isEqualTo(ReportStatus.REJECTED);

        CommunityReportEntity another = reportOnComment(11);
        AdminReportProcessResponseDTO unchanged =
                service.process(another.getCommunityReportId(), ReportAction.RESTORE);
        assertThat(unchanged.targetStatus()).isEqualTo(CommunityStatus.VISIBLE);
        assertThat(reportStatus(another)).isEqualTo(ReportStatus.REJECTED);
    }

    @Test
    @DisplayName("이미 처리한 신고는 ALREADY_PROCESSED, 없는 신고는 NOT_FOUND, 감사로그는 남기지 않는다")
    void alreadyProcessedAndMissing() {
        CommunityReportEntity report = reportOnPost(10);
        service.process(report.getCommunityReportId(), ReportAction.HIDE);

        assertError(
                () -> service.process(report.getCommunityReportId(), ReportAction.RESTORE),
                ErrorType.COMMUNITY_REPORT_ALREADY_PROCESSED);
        assertError(
                () -> service.process(999_999L, ReportAction.HIDE),
                ErrorType.COMMUNITY_REPORT_NOT_FOUND);
        verify(auditLogService, org.mockito.Mockito.times(1))
                .log(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("작성자가 이미 삭제한 대상은 어떤 처리도 할 수 없고 신고도 그대로 남는다")
    void deletedTargetUnavailable() {
        CommunityReportEntity report = reportOnPost(10);
        post.markDeleted();
        postRepository.flush();

        for (ReportAction action : ReportAction.values()) {
            assertError(
                    () -> service.process(report.getCommunityReportId(), action),
                    ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        }
        assertThat(reportStatus(report)).isEqualTo(ReportStatus.PENDING);
        verify(auditLogService, never()).log(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("처리하는 관리자가 없으면(인증 실패) 아무것도 바꾸지 않는다")
    void noAdminNoChange() {
        CommunityReportEntity report = reportOnPost(10);
        when(auditActorProvider.getCurrentAdminId())
                .thenThrow(new AppException(ErrorType.AUDIT_LOG_ACTOR_NOT_FOUND));

        assertError(
                () -> service.process(report.getCommunityReportId(), ReportAction.HIDE),
                ErrorType.AUDIT_LOG_ACTOR_NOT_FOUND);
        assertThat(reportStatus(report)).isEqualTo(ReportStatus.PENDING);
        assertThat(postRepository.findById(post.getCommunityPostId()).orElseThrow().getStatus())
                .isEqualTo(CommunityStatus.VISIBLE);
    }
}
