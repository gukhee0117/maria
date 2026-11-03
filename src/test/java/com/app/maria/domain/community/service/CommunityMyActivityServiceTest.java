package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.CommentResponseDTO;
import com.app.maria.domain.community.dto.response.MySummaryResponseDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.dto.response.PostResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({
    JpaConfig.class,
    TransactionConfig.class,
    CommunityMyActivityServiceImpl.class,
    CommunityPostAssembler.class,
    CommunityCommentAssembler.class
})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityMyActivityServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired private CommunityMyActivityService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private JdbcTemplate jdbc;

    private long me;
    private long other;

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
        jdbc.execute("DELETE FROM community_comment");
        jdbc.execute("DELETE FROM community_post");
        jdbc.execute("DELETE FROM customer");
        me = insertCustomer("반도체개미", true);
        other = insertCustomer("장기보유러", false);
    }

    private long insertCustomer(String nickname, boolean publicType) {
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash, nickname,"
                        + " investor_type_public) VALUES (?, ?, ?, ?, ?, ?)",
                "홍길동",
                "1990-01-01",
                "ACTIVE",
                "hash-" + System.nanoTime(),
                nickname,
                publicType);
        return jdbc.queryForObject("SELECT MAX(customer_id) FROM customer", Long.class);
    }

    private CommunityPostEntity post(long customerId, String ticker, String content) {
        return postRepository.saveAndFlush(
                CommunityPostEntity.write(customerId, ticker, content, null, NOW));
    }

    private CommunityCommentEntity comment(
            CommunityPostEntity post, long customerId, String content) {
        return commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(), null, customerId, content, null, NOW));
    }

    @Test
    @DisplayName("내 글은 최신순이고 남의 글·삭제한 글은 나오지 않으며 ticker로 좁힐 수 있다")
    void myPosts() {
        CommunityPostEntity a = post(me, "000660", "내 글 1");
        CommunityPostEntity b = post(me, "005930", "내 글 2");
        post(me, "000660", "지운 글").markDeleted();
        post(other, "000660", "남의 글");
        postRepository.flush();

        List<PostResponseDTO> all = service.myPosts(me, null, null, 10).items();
        List<PostResponseDTO> hynix = service.myPosts(me, "000660", null, 10).items();

        assertThat(all)
                .extracting(PostResponseDTO::id)
                .containsExactly(b.getCommunityPostId(), a.getCommunityPostId());
        assertThat(all).allMatch(PostResponseDTO::mine);
        assertThat(hynix).extracting(PostResponseDTO::id).containsExactly(a.getCommunityPostId());
        assertThat(service.myPosts(me, " ", null, 10).items()).hasSize(2);
    }

    @Test
    @DisplayName("내 글 목록도 커서로 겹침·누락 없이 이어지고 딱 맞는 마지막 쪽은 nextCursor가 null이다")
    void myPostsPaging() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            expected.add(0, post(me, "000660", "글 " + i).getCommunityPostId());
        }

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            PostPageResponseDTO page = service.myPosts(me, null, cursor, 2);
            page.items().forEach(i -> seen.add(i.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(2);
    }

    @Test
    @DisplayName("내 댓글은 최신순이고 ticker는 그 종목 글에 단 댓글만 좁힌다. 남의 댓글·끼루 댓글은 나오지 않는다")
    void myComments() {
        CommunityPostEntity hynix = post(other, "000660", "하이닉스 글");
        CommunityPostEntity samsung = post(other, "005930", "삼성 글");
        CommunityCommentEntity onHynix = comment(hynix, me, "하이닉스 댓글");
        CommunityCommentEntity onSamsung = comment(samsung, me, "삼성 댓글");
        comment(hynix, other, "남의 댓글");
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofAi(hynix.getCommunityPostId(), null, "끼루", null, NOW));
        comment(hynix, me, "지운 댓글").markDeleted();
        commentRepository.flush();

        List<CommentResponseDTO> all = service.myComments(me, null, null, 10).items();
        List<CommentResponseDTO> onlyHynix = service.myComments(me, "000660", null, 10).items();

        assertThat(all)
                .extracting(CommentResponseDTO::id)
                .containsExactly(
                        onSamsung.getCommunityCommentId(), onHynix.getCommunityCommentId());
        assertThat(onlyHynix)
                .extracting(CommentResponseDTO::id)
                .containsExactly(onHynix.getCommunityCommentId());
        assertThat(all).allMatch(CommentResponseDTO::mine);
    }

    @Test
    @DisplayName("내 댓글도 딱 size개면 다음 쪽이 없다")
    void myCommentsExactPage() {
        CommunityPostEntity p = post(other, "000660", "글");
        comment(p, me, "1");
        comment(p, me, "2");

        CommentPageResponseDTO page = service.myComments(me, null, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("이상한 종목 문자열은 거부한다")
    void invalidTicker() {
        assertThatThrownBy(() -> service.myPosts(me, "A'; DROP", null, 10))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.COMMUNITY_TICKER_NOT_FOUND));
        assertThatThrownBy(() -> service.myComments(me, "A'; DROP", null, 10))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.COMMUNITY_TICKER_NOT_FOUND));
    }

    @Test
    @DisplayName("프로필 카드: 삭제한 글·댓글은 세지 않고 남의 것·끼루 댓글도 세지 않는다")
    void summary() {
        CommunityPostEntity mine = post(me, "000660", "내 글");
        post(me, "000660", "지운 글").markDeleted();
        CommunityPostEntity others = post(other, "000660", "남의 글");
        comment(others, me, "내 댓글 1");
        comment(others, me, "내 댓글 2");
        comment(others, me, "지운 댓글").markDeleted();
        comment(others, other, "남의 댓글");
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofAi(mine.getCommunityPostId(), null, "끼루", null, NOW));
        commentRepository.flush();

        MySummaryResponseDTO summary = service.summary(me);

        assertThat(summary.nickname()).isEqualTo("반도체개미");
        assertThat(summary.postCount()).isEqualTo(1);
        assertThat(summary.commentCount()).isEqualTo(2);
        assertThat(summary.investorTypePublic()).isTrue();
        assertThat(service.summary(other).investorTypePublic()).isFalse();
    }

    @Test
    @DisplayName("닉네임이 없으면 null이고 없는 고객은 CUSTOMER_NOT_FOUND다")
    void summaryEdgeCases() {
        long nobody = insertCustomer(null, false);

        assertThat(service.summary(nobody).nickname()).isNull();
        assertThatThrownBy(() -> service.summary(999_999L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.CUSTOMER_NOT_FOUND));
    }
}
