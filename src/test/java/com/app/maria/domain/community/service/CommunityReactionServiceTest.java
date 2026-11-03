package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.response.LikeResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
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
@Import({JpaConfig.class, TransactionConfig.class, CommunityReactionServiceImpl.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityReactionServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired private CommunityReactionService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
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
        jdbc.execute("DELETE FROM community_comment_like");
        jdbc.execute("DELETE FROM community_post_reaction");
        jdbc.execute("DELETE FROM community_comment");
        jdbc.execute("DELETE FROM community_post");
        when(clock.now()).thenReturn(NOW);
        post = postRepository.saveAndFlush(CommunityPostEntity.write(1L, "000660", "글", null, NOW));
        comment =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofCustomer(
                                post.getCommunityPostId(), null, 2L, "댓글", null, NOW));
    }

    private static void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private long storedPostLikes() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM community_post_reaction", Long.class);
    }

    @Test
    @DisplayName("글 좋아요는 켜면 1, 같은 사람이 또 켜도 1이고, 다른 사람이 켜면 2가 된다")
    void likePostIsIdempotent() {
        Long id = post.getCommunityPostId();

        assertThat(service.likePost(10L, id)).isEqualTo(new LikeResponseDTO(true, 1));
        assertThat(service.likePost(10L, id)).isEqualTo(new LikeResponseDTO(true, 1));
        assertThat(service.likePost(11L, id)).isEqualTo(new LikeResponseDTO(true, 2));
        assertThat(storedPostLikes()).isEqualTo(2);
    }

    @Test
    @DisplayName("끄면 내 것만 사라지고, 안 눌렀는데 꺼도 오류 없이 같은 결과다")
    void unlikePostIsIdempotent() {
        Long id = post.getCommunityPostId();
        service.likePost(10L, id);
        service.likePost(11L, id);

        assertThat(service.unlikePost(10L, id)).isEqualTo(new LikeResponseDTO(false, 1));
        assertThat(service.unlikePost(10L, id)).isEqualTo(new LikeResponseDTO(false, 1));
        assertThat(service.unlikePost(99L, id)).isEqualTo(new LikeResponseDTO(false, 1));
    }

    @Test
    @DisplayName("없는 글은 NOT_FOUND, 숨김·삭제된 글에는 켤 수 없고 이미 있던 좋아요는 끌 수 있다")
    void postAvailability() {
        Long id = post.getCommunityPostId();
        service.likePost(10L, id);
        post.hide();
        postRepository.flush();

        assertError(() -> service.likePost(11L, id), ErrorType.COMMUNITY_POST_NOT_VISIBLE);
        assertError(() -> service.likePost(10L, 999_999L), ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertError(() -> service.unlikePost(10L, 999_999L), ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertThat(service.unlikePost(10L, id).likeCount()).isZero();
        post.markDeleted();
        postRepository.flush();
        assertError(() -> service.likePost(11L, id), ErrorType.COMMUNITY_POST_NOT_VISIBLE);
    }

    @Test
    @DisplayName("댓글 좋아요도 켜기·끄기가 멱등이고 사람별로 센다")
    void commentLikes() {
        Long id = comment.getCommunityCommentId();

        assertThat(service.likeComment(10L, id)).isEqualTo(new LikeResponseDTO(true, 1));
        assertThat(service.likeComment(10L, id)).isEqualTo(new LikeResponseDTO(true, 1));
        assertThat(service.likeComment(11L, id)).isEqualTo(new LikeResponseDTO(true, 2));
        assertThat(service.unlikeComment(10L, id)).isEqualTo(new LikeResponseDTO(false, 1));
        assertThat(service.unlikeComment(10L, id)).isEqualTo(new LikeResponseDTO(false, 1));
    }

    @Test
    @DisplayName("없는·숨김·삭제된 댓글에는 좋아요를 켤 수 없다")
    void commentAvailability() {
        Long id = comment.getCommunityCommentId();
        assertError(
                () -> service.likeComment(10L, 999_999L), ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
        assertError(
                () -> service.unlikeComment(10L, 999_999L), ErrorType.COMMUNITY_COMMENT_NOT_FOUND);

        comment.hide();
        commentRepository.flush();
        assertError(() -> service.likeComment(10L, id), ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
        comment.markDeleted();
        commentRepository.flush();
        assertError(() -> service.likeComment(10L, id), ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("숨겨지거나 삭제된 글의 댓글에는 좋아요를 켤 수 없고 이미 있던 좋아요는 끌 수 있다")
    void commentOnUnavailablePost() {
        Long id = comment.getCommunityCommentId();
        service.likeComment(10L, id);
        post.hide();
        postRepository.flush();

        assertError(() -> service.likeComment(11L, id), ErrorType.COMMUNITY_POST_NOT_VISIBLE);
        assertThat(service.unlikeComment(10L, id).likeCount()).isZero();
        post.markDeleted();
        postRepository.flush();
        assertError(() -> service.likeComment(11L, id), ErrorType.COMMUNITY_POST_NOT_VISIBLE);
    }

    @Test
    @DisplayName("글 좋아요와 댓글 좋아요는 서로 세지 않는다")
    void separateCounters() {
        service.likePost(10L, post.getCommunityPostId());
        service.likeComment(10L, comment.getCommunityCommentId());
        service.likeComment(11L, comment.getCommunityCommentId());

        assertThat(service.likePost(11L, post.getCommunityPostId()).likeCount()).isEqualTo(2);
        assertThat(service.unlikeComment(10L, comment.getCommunityCommentId()).likeCount())
                .isEqualTo(1);
    }
}
