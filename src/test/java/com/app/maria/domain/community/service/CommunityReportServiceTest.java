package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.request.ReportRequestDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.repository.CommunityReportRepository;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.ReportReason;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
@Import({JpaConfig.class, TransactionConfig.class, CommunityReportServiceImpl.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityReportServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired private CommunityReportService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private CommunityReportRepository reportRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private BusinessClockService clock;

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
        when(clock.now()).thenReturn(NOW);
        post =
                postRepository.saveAndFlush(
                        CommunityPostEntity.write(1L, "000660", "신고당할 글", null, NOW));
        comment =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofCustomer(
                                post.getCommunityPostId(), null, 2L, "신고당할 댓글", null, NOW));
    }

    private ReportRequestDTO onPost(ReportReason reason) {
        return new ReportRequestDTO(post.getCommunityPostId(), null, reason);
    }

    private ReportRequestDTO onComment(ReportReason reason) {
        return new ReportRequestDTO(null, comment.getCommunityCommentId(), reason);
    }

    private static void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private CommunityStatus postStatus() {
        entityManager.flush();
        entityManager.clear();
        return postRepository.findById(post.getCommunityPostId()).orElseThrow().getStatus();
    }

    private CommunityStatus commentStatus() {
        entityManager.flush();
        entityManager.clear();
        return commentRepository
                .findById(comment.getCommunityCommentId())
                .orElseThrow()
                .getStatus();
    }

    @Test
    @DisplayName("글 신고는 4건까지 보이고 5건째에 자동으로 숨겨진다")
    void postAutoHideAtFive() {
        for (long reporter = 10; reporter < 14; reporter++) {
            service.report(reporter, onPost(ReportReason.SPAM));
        }
        assertThat(postStatus()).isEqualTo(CommunityStatus.VISIBLE);

        service.report(14L, onPost(ReportReason.ABUSE));

        assertThat(postStatus()).isEqualTo(CommunityStatus.HIDDEN);
    }

    @Test
    @DisplayName("댓글 신고도 5건째에 숨겨지고, 글 신고와 서로 세지 않는다")
    void commentAutoHideAtFive() {
        service.report(10L, onPost(ReportReason.SPAM));
        service.report(11L, onPost(ReportReason.SPAM));
        for (long reporter = 10; reporter < 14; reporter++) {
            service.report(reporter, onComment(ReportReason.SPAM));
        }
        assertThat(commentStatus()).isEqualTo(CommunityStatus.VISIBLE);

        service.report(14L, onComment(ReportReason.PRIVACY));

        assertThat(commentStatus()).isEqualTo(CommunityStatus.HIDDEN);
        assertThat(postStatus()).isEqualTo(CommunityStatus.VISIBLE);
    }

    @Test
    @DisplayName("같은 사람이 같은 글·댓글을 두 번 신고하면 DUPLICATED이고 한 건으로만 센다")
    void duplicateReport() {
        service.report(10L, onPost(ReportReason.SPAM));
        service.report(10L, onComment(ReportReason.SPAM));

        assertError(
                () -> service.report(10L, onPost(ReportReason.ABUSE)),
                ErrorType.COMMUNITY_REPORT_DUPLICATED);
        assertError(
                () -> service.report(10L, onComment(ReportReason.ABUSE)),
                ErrorType.COMMUNITY_REPORT_DUPLICATED);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_report", Long.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사람이 같은 글을 계속 눌러도 5건이 되지 않아 숨겨지지 않는다")
    void oneReporterCannotHideAlone() {
        service.report(10L, onPost(ReportReason.SPAM));
        for (int i = 0; i < 6; i++) {
            assertError(
                    () -> service.report(10L, onPost(ReportReason.SPAM)),
                    ErrorType.COMMUNITY_REPORT_DUPLICATED);
        }

        assertThat(postStatus()).isEqualTo(CommunityStatus.VISIBLE);
    }

    @Test
    @DisplayName("글과 댓글을 둘 다 지정하거나 하나도 지정하지 않으면 TARGET_INVALID이고 저장하지 않는다")
    void exactlyOneTarget() {
        assertError(
                () -> service.report(10L, new ReportRequestDTO(null, null, ReportReason.SPAM)),
                ErrorType.COMMUNITY_REPORT_TARGET_INVALID);
        assertError(
                () ->
                        service.report(
                                10L,
                                new ReportRequestDTO(
                                        post.getCommunityPostId(),
                                        comment.getCommunityCommentId(),
                                        ReportReason.SPAM)),
                ErrorType.COMMUNITY_REPORT_TARGET_INVALID);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_report", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("없는 글·댓글, 이미 숨겨졌거나 삭제된 글·댓글은 신고할 수 없다")
    void unavailableTargets() {
        assertError(
                () -> service.report(10L, new ReportRequestDTO(999_999L, null, ReportReason.SPAM)),
                ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertError(
                () -> service.report(10L, new ReportRequestDTO(null, 999_999L, ReportReason.SPAM)),
                ErrorType.COMMUNITY_COMMENT_NOT_FOUND);

        post.hide();
        postRepository.flush();
        assertError(
                () -> service.report(10L, onPost(ReportReason.SPAM)),
                ErrorType.COMMUNITY_POST_NOT_VISIBLE);
        post.markDeleted();
        postRepository.flush();
        assertError(
                () -> service.report(10L, onPost(ReportReason.SPAM)),
                ErrorType.COMMUNITY_POST_NOT_VISIBLE);

        comment.hide();
        commentRepository.flush();
        assertError(
                () -> service.report(10L, onComment(ReportReason.SPAM)),
                ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
        comment.markDeleted();
        commentRepository.flush();
        assertError(
                () -> service.report(10L, onComment(ReportReason.SPAM)),
                ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("숨겨진 뒤에는 더 신고할 수 없다")
    void noMoreReportsAfterHide() {
        for (long reporter = 10; reporter < 15; reporter++) {
            service.report(reporter, onPost(ReportReason.SPAM));
        }

        assertError(
                () -> service.report(20L, onPost(ReportReason.SPAM)),
                ErrorType.COMMUNITY_POST_NOT_VISIBLE);
    }

    @Test
    @DisplayName("신고는 PENDING으로 저장되고 사유·시각이 남는다")
    void storedAsPending() {
        service.report(10L, onPost(ReportReason.PRIVACY));

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT status FROM community_report", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT reason FROM community_report", String.class))
                .isEqualTo("PRIVACY");
        assertThat(reportRepository.findAll().get(0).getCreatedAt()).isEqualTo(NOW);
    }
}
