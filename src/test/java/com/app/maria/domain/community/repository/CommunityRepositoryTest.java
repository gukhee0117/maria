package com.app.maria.domain.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityCommentLikeEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.entity.CommunityPostReactionEntity;
import com.app.maria.domain.community.entity.CommunityReportEntity;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.domain.community.type.ReactionType;
import com.app.maria.domain.community.type.ReportReason;
import com.app.maria.domain.community.type.ReportStatus;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import java.time.LocalDateTime;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({JpaConfig.class, TransactionConfig.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);
    private static final long NO_CURSOR = Long.MAX_VALUE;
    private static final PageRequest TEN = PageRequest.of(0, 10);

    @Autowired private CommunityPostRepository posts;
    @Autowired private CommunityCommentRepository comments;
    @Autowired private CommunityPostReactionRepository reactions;
    @Autowired private CommunityCommentLikeRepository commentLikes;
    @Autowired private CommunityReportRepository reports;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    void createSchema() {
        CommunityTestSchema.create(jdbc);
    }

    @AfterAll
    void dropSchema() {
        CommunityTestSchema.drop(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM community_report");
        jdbc.execute("DELETE FROM community_comment_like");
        jdbc.execute("DELETE FROM community_post_reaction");
        jdbc.execute("DELETE FROM community_comment");
        jdbc.execute("DELETE FROM community_post");
    }

    private CommunityPostEntity post(long customerId, String ticker, String content) {
        return post(customerId, ticker, content, NOW);
    }

    private CommunityPostEntity post(
            long customerId, String ticker, String content, LocalDateTime at) {
        return posts.saveAndFlush(CommunityPostEntity.write(customerId, ticker, content, null, at));
    }

    private CommunityCommentEntity comment(CommunityPostEntity post, long customerId) {
        return comments.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(), null, customerId, "댓글", null, NOW));
    }

    private static List<Long> ids(List<CommunityPostEntity> list) {
        return list.stream().map(CommunityPostEntity::getCommunityPostId).toList();
    }

    @Test
    @DisplayName("목록: 전체·종목·일반 글 필터와 최신순, 삭제 글(댓글 없음)은 빠진다")
    void feedFilters() {
        CommunityPostEntity p1 = post(7, "000660", "HBM 이야기");
        CommunityPostEntity p2 = post(8, "000660", "100% 확신");
        CommunityPostEntity p3 = post(7, null, "제도 질문입니다");
        CommunityPostEntity hidden = post(9, "005930", "숨김 글 secret");
        hidden.hide();
        CommunityPostEntity deletedAlone = post(7, null, "지운 글 A");
        deletedAlone.markDeleted();
        CommunityPostEntity deletedWithComment = post(7, null, "지운 글 B");
        comment(deletedWithComment, 8);
        deletedWithComment.markDeleted();
        posts.flush();

        List<CommunityPostEntity> all = posts.findFeed(NO_CURSOR, true, false, "", false, "", TEN);
        List<CommunityPostEntity> hynix =
                posts.findFeed(NO_CURSOR, false, false, "000660", false, "", TEN);
        List<CommunityPostEntity> general =
                posts.findFeed(NO_CURSOR, false, true, "", false, "", TEN);

        assertThat(ids(all))
                .containsExactly(
                        deletedWithComment.getCommunityPostId(),
                        hidden.getCommunityPostId(),
                        p3.getCommunityPostId(),
                        p2.getCommunityPostId(),
                        p1.getCommunityPostId());
        assertThat(ids(hynix)).containsExactly(p2.getCommunityPostId(), p1.getCommunityPostId());
        assertThat(ids(general))
                .containsExactly(deletedWithComment.getCommunityPostId(), p3.getCommunityPostId());
    }

    @Test
    @DisplayName("목록: 커서와 개수 제한으로 겹치거나 빠지는 글 없이 이어진다")
    void feedCursor() {
        List<Long> created = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(post(7, "000660", "글 " + i).getCommunityPostId());
        }
        java.util.Collections.reverse(created); // 최신순 기대값

        List<Long> page1 =
                ids(posts.findFeed(NO_CURSOR, true, false, "", false, "", PageRequest.of(0, 2)));
        List<Long> page2 =
                ids(posts.findFeed(page1.get(1), true, false, "", false, "", PageRequest.of(0, 2)));
        List<Long> page3 =
                ids(posts.findFeed(page2.get(1), true, false, "", false, "", PageRequest.of(0, 2)));

        assertThat(page1).containsExactly(created.get(0), created.get(1));
        assertThat(page2).containsExactly(created.get(2), created.get(3));
        assertThat(page3).containsExactly(created.get(4));
    }

    @Test
    @DisplayName("검색: 대소문자 무시, %와 _는 글자 그대로, 숨김 글 본문은 검색되지 않는다")
    void keywordSearch() {
        CommunityPostEntity hbm = post(7, "000660", "HBM 증설 소식");
        CommunityPostEntity percent = post(7, "000660", "수익률 100% 라는데");
        CommunityPostEntity underscore = post(7, "000660", "a_b 같은 코드");
        CommunityPostEntity plain = post(7, "000660", "aXb 평범한 글");
        CommunityPostEntity bang = post(7, "000660", "대박! 이건 !중요");
        CommunityPostEntity hidden = post(7, "000660", "비밀키워드 포함");
        hidden.hide();
        posts.flush();

        assertThat(ids(search("hbm"))).containsExactly(hbm.getCommunityPostId());
        assertThat(ids(search("100%"))).containsExactly(percent.getCommunityPostId());
        assertThat(ids(search("%"))).containsExactly(percent.getCommunityPostId());
        assertThat(ids(search("a_b"))).containsExactly(underscore.getCommunityPostId());
        assertThat(ids(search("!"))).containsExactly(bang.getCommunityPostId());
        assertThat(ids(search("박!"))).containsExactly(bang.getCommunityPostId());
        assertThat(ids(search("비밀키워드"))).isEmpty();
        assertThat(plain.getCommunityPostId()).isNotNull();
    }

    private List<CommunityPostEntity> search(String keyword) {
        String escaped =
                keyword.toLowerCase().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return posts.findFeed(NO_CURSOR, true, false, "", true, "%" + escaped + "%", TEN);
    }

    @Test
    @DisplayName("인기글: 종목 안에서 보이는 댓글 수 순, 댓글 0개·오래된 글·숨김 글은 빠진다")
    void popular() {
        CommunityPostEntity a = post(7, "000660", "댓글 3개");
        CommunityPostEntity b = post(7, "000660", "보이는 댓글 1개+숨김 댓글 3개");
        post(7, "000660", "댓글 없음");
        CommunityPostEntity other = post(7, "005930", "다른 종목 댓글 5개");
        CommunityPostEntity old = post(7, "000660", "오래됨", NOW.minusDays(10));
        CommunityPostEntity hiddenPost = post(7, "000660", "숨김 글");
        for (int i = 0; i < 3; i++) comment(a, 8);
        comment(b, 8);
        for (int i = 0; i < 3; i++) comment(b, 8).hide(); // 숨김 댓글이 세어지면 b(4)가 a(3)를 앞지른다
        for (int i = 0; i < 5; i++) comment(other, 8);
        for (int i = 0; i < 9; i++) comment(old, 8);
        comment(hiddenPost, 8);
        hiddenPost.hide();
        comments.flush();
        posts.flush();
        LocalDateTime since = NOW.minusDays(7);

        List<CommunityPostEntity> hynix = posts.findPopular(since, false, false, "000660", TEN);
        List<CommunityPostEntity> all = posts.findPopular(since, true, false, "", TEN);

        assertThat(ids(hynix)).containsExactly(a.getCommunityPostId(), b.getCommunityPostId());
        assertThat(ids(all))
                .containsExactly(
                        other.getCommunityPostId(), a.getCommunityPostId(), b.getCommunityPostId());
    }

    @Test
    @DisplayName("인기글: 일반 글(종목 없음)만 따로 볼 수 있다")
    void popularGeneral() {
        CommunityPostEntity general = post(7, null, "종목 없는 글");
        CommunityPostEntity ticker = post(7, "000660", "종목 글");
        comment(general, 8);
        comment(ticker, 8);
        comment(ticker, 8);

        List<CommunityPostEntity> result =
                posts.findPopular(NOW.minusDays(7), false, true, "", TEN);

        assertThat(ids(result)).containsExactly(general.getCommunityPostId());
    }

    @Test
    @DisplayName("인기글: 댓글 수가 같으면 더 최근 글이 앞이다")
    void popularTieBreak() {
        CommunityPostEntity older = post(7, "000660", "먼저");
        CommunityPostEntity newer = post(7, "000660", "나중");
        comment(older, 8);
        comment(newer, 8);

        List<CommunityPostEntity> result =
                posts.findPopular(NOW.minusDays(7), false, false, "000660", TEN);

        assertThat(ids(result))
                .containsExactly(newer.getCommunityPostId(), older.getCommunityPostId());
    }

    @Test
    @DisplayName("종목 순위: 기간 안의 보이는 종목 글 수 순, 같으면 종목코드 순")
    void rankTickers() {
        post(7, "000660", "1");
        post(7, "000660", "2");
        post(7, "005930", "3");
        post(7, "005930", "4");
        post(7, "035420", "5");
        post(7, null, "일반 글은 순위에 없다");
        post(7, "000660", "어제 글", NOW.minusDays(1));
        post(7, "373220", "숨김").hide();
        posts.flush();

        List<CommunityTickerCount> ranking =
                posts.rankTickers(
                        NOW.toLocalDate().atStartOfDay(),
                        NOW.toLocalDate().plusDays(1).atStartOfDay(),
                        TEN);

        assertThat(ranking)
                .extracting(CommunityTickerCount::getTicker)
                .containsExactly("000660", "005930", "035420");
        assertThat(ranking)
                .extracting(CommunityTickerCount::getPostCount)
                .containsExactly(2L, 2L, 1L);
    }

    @Test
    @DisplayName("내가 쓴 글: 삭제 제외, ticker를 주면 그 종목만, 다른 사람 글은 안 나온다")
    void minePosts() {
        CommunityPostEntity mine1 = post(7, "000660", "내 글 1");
        CommunityPostEntity mine2 = post(7, "005930", "내 글 2");
        post(7, "000660", "지움").markDeleted();
        post(8, "000660", "남의 글");
        posts.flush();

        assertThat(ids(posts.findMine(7L, NO_CURSOR, true, "", TEN)))
                .containsExactly(mine2.getCommunityPostId(), mine1.getCommunityPostId());
        assertThat(ids(posts.findMine(7L, NO_CURSOR, false, "000660", TEN)))
                .containsExactly(mine1.getCommunityPostId());
        assertThat(posts.countByCustomerIdAndStatusNot(7L, CommunityStatus.DELETED)).isEqualTo(2);
    }

    @Test
    @DisplayName("댓글 목록: 오래된 순, 삭제 제외, 숨김 포함, 커서로 이어진다")
    void thread() {
        CommunityPostEntity p = post(7, "000660", "글");
        CommunityCommentEntity c1 = comment(p, 8);
        CommunityCommentEntity gone = comment(p, 8);
        gone.markDeleted();
        CommunityCommentEntity c3 = comment(p, 9);
        c3.hide();
        CommunityCommentEntity c4 = comment(p, 8);
        comments.flush();

        List<CommunityCommentEntity> all = comments.findThread(p.getCommunityPostId(), 0L, TEN);
        List<CommunityCommentEntity> next =
                comments.findThread(p.getCommunityPostId(), c1.getCommunityCommentId(), TEN);

        assertThat(all)
                .extracting(CommunityCommentEntity::getCommunityCommentId)
                .containsExactly(
                        c1.getCommunityCommentId(),
                        c3.getCommunityCommentId(),
                        c4.getCommunityCommentId());
        assertThat(next).hasSize(2);
    }

    @Test
    @DisplayName("댓글 수 집계는 보이는 댓글만 글별로 센다")
    void commentCounts() {
        CommunityPostEntity p1 = post(7, "000660", "1");
        CommunityPostEntity p2 = post(7, "000660", "2");
        CommunityPostEntity p3 = post(7, "000660", "3");
        comment(p1, 8);
        comment(p1, 8);
        comment(p2, 8).hide();
        comments.flush();

        List<CommunityIdCount> counts =
                comments.countVisibleByPostIds(
                        List.of(
                                p1.getCommunityPostId(),
                                p2.getCommunityPostId(),
                                p3.getCommunityPostId()));

        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).getId()).isEqualTo(p1.getCommunityPostId());
        assertThat(counts.get(0).getTotal()).isEqualTo(2L);
    }

    @Test
    @DisplayName("내가 쓴 댓글: ticker를 주면 그 종목 글에 쓴 댓글만")
    void mineComments() {
        CommunityPostEntity hynix = post(7, "000660", "하이닉스");
        CommunityPostEntity samsung = post(7, "005930", "삼성");
        CommunityCommentEntity onHynix = comment(hynix, 8);
        CommunityCommentEntity onSamsung = comment(samsung, 8);
        comment(hynix, 9);

        assertThat(comments.findMine(8L, NO_CURSOR, true, "", TEN))
                .extracting(CommunityCommentEntity::getCommunityCommentId)
                .containsExactly(
                        onSamsung.getCommunityCommentId(), onHynix.getCommunityCommentId());
        assertThat(comments.findMine(8L, NO_CURSOR, false, "000660", TEN))
                .extracting(CommunityCommentEntity::getCommunityCommentId)
                .containsExactly(onHynix.getCommunityCommentId());
    }

    @Test
    @DisplayName("끼루가 이미 답했는지: 댓글에서 호출한 경우와 글 본문에서 호출한 경우를 구분한다")
    void kkiruAnsweredChecks() {
        CommunityPostEntity p = post(7, "000660", "@끼루 공시 있나요");
        CommunityCommentEntity called = comment(p, 8);

        assertThat(
                        comments.existsByCommunityPostIdAndParentCommentIdIsNullAndAuthorType(
                                p.getCommunityPostId(), AuthorType.AI))
                .isFalse();
        comments.saveAndFlush(
                CommunityCommentEntity.ofAi(p.getCommunityPostId(), null, "글 본문 답", null, NOW));
        comments.saveAndFlush(
                CommunityCommentEntity.ofAi(
                        p.getCommunityPostId(), called.getCommunityCommentId(), "댓글 답", null, NOW));

        assertThat(
                        comments.existsByCommunityPostIdAndParentCommentIdIsNullAndAuthorType(
                                p.getCommunityPostId(), AuthorType.AI))
                .isTrue();
        assertThat(
                        comments.existsByCommunityPostIdAndParentCommentIdAndAuthorType(
                                p.getCommunityPostId(),
                                called.getCommunityCommentId(),
                                AuthorType.AI))
                .isTrue();
        assertThat(
                        comments.existsByCommunityPostIdAndParentCommentIdAndAuthorType(
                                p.getCommunityPostId(), 999_999L, AuthorType.AI))
                .isFalse();
    }

    @Test
    @DisplayName("끼루 호출 횟수는 글 단위, 사용자 단위(글+댓글)로 세고, 후속 질문·범위 밖은 세지 않는다")
    void kkiruCallCounts() {
        List<KkiruStatus> counted =
                List.of(
                        KkiruStatus.PENDING,
                        KkiruStatus.ANSWERED,
                        KkiruStatus.NO_SOURCE,
                        KkiruStatus.FAILED);
        CommunityPostEntity p =
                posts.saveAndFlush(
                        CommunityPostEntity.write(
                                7L, "000660", "@끼루 질문", KkiruStatus.PENDING, NOW));
        comments.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        p.getCommunityPostId(), null, 7L, "@끼루 또", KkiruStatus.PENDING, NOW));
        // 한도에 세지 않는 호출: 후속 질문(끼루 댓글에 다시 물음)과 범위 밖 질문
        comments.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        p.getCommunityPostId(), null, 7L, "@끼루 후속", KkiruStatus.FOLLOW_UP, NOW));
        comments.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        p.getCommunityPostId(),
                        null,
                        7L,
                        "@끼루 사도 돼요?",
                        KkiruStatus.OUT_OF_SCOPE,
                        NOW));
        comment(p, 7);

        assertThat(comments.countByCommunityPostIdAndKkiruStatusIn(p.getCommunityPostId(), counted))
                .isEqualTo(1);
        assertThat(
                        posts.countByCustomerIdAndKkiruStatusInAndCreatedAtGreaterThanEqual(
                                7L, counted, NOW.toLocalDate().atStartOfDay()))
                .isEqualTo(1);
        assertThat(
                        comments.countByCustomerIdAndKkiruStatusInAndCreatedAtGreaterThanEqual(
                                7L, counted, NOW.toLocalDate().atStartOfDay()))
                .isEqualTo(1);
        assertThat(
                        comments.countByCustomerIdAndKkiruStatusInAndCreatedAtGreaterThanEqual(
                                7L, counted, NOW.toLocalDate().plusDays(1).atStartOfDay()))
                .isZero();
    }

    @Test
    @DisplayName("글 좋아요: 개수·내가 누른 글 조회, 스크랩은 세지 않고, 없는 좋아요 취소는 0건이다")
    void postLikes() {
        CommunityPostEntity p1 = post(7, "000660", "1");
        CommunityPostEntity p2 = post(7, "000660", "2");
        reactions.saveAndFlush(
                CommunityPostReactionEntity.of(
                        p1.getCommunityPostId(), 8L, ReactionType.LIKE, NOW));
        reactions.saveAndFlush(
                CommunityPostReactionEntity.of(
                        p1.getCommunityPostId(), 9L, ReactionType.LIKE, NOW));
        reactions.saveAndFlush(
                CommunityPostReactionEntity.of(
                        p1.getCommunityPostId(), 9L, ReactionType.SCRAP, NOW));

        List<CommunityIdCount> counts =
                reactions.countLikesByPostIds(
                        List.of(p1.getCommunityPostId(), p2.getCommunityPostId()));

        assertThat(counts).hasSize(1);
        assertThat(counts.get(0).getTotal()).isEqualTo(2L);
        assertThat(
                        reactions.findLikedPostIds(
                                8L, List.of(p1.getCommunityPostId(), p2.getCommunityPostId())))
                .containsExactly(p1.getCommunityPostId());
        assertThat(reactions.deleteReaction(p1.getCommunityPostId(), 8L, ReactionType.LIKE))
                .isEqualTo(1);
        assertThat(reactions.deleteReaction(p1.getCommunityPostId(), 8L, ReactionType.LIKE))
                .isZero();
    }

    @Test
    @DisplayName("같은 사람이 같은 글에 좋아요를 두 번 저장하면 DB가 막는다")
    void duplicateLikeRejected() {
        CommunityPostEntity p = post(7, "000660", "1");
        reactions.saveAndFlush(
                CommunityPostReactionEntity.of(p.getCommunityPostId(), 8L, ReactionType.LIKE, NOW));

        assertThatThrownBy(
                        () -> {
                            jdbc.update(
                                    "INSERT INTO community_post_reaction (community_post_id,"
                                            + " customer_id, reaction_type, created_at) VALUES (?,?,?,?)",
                                    p.getCommunityPostId(),
                                    8L,
                                    "LIKE",
                                    NOW);
                        })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("댓글 좋아요: 개수·내가 누른 댓글·취소")
    void commentLikes() {
        CommunityPostEntity p = post(7, "000660", "1");
        CommunityCommentEntity c = comment(p, 8);
        commentLikes.saveAndFlush(
                CommunityCommentLikeEntity.of(c.getCommunityCommentId(), 9L, NOW));
        commentLikes.saveAndFlush(
                CommunityCommentLikeEntity.of(c.getCommunityCommentId(), 10L, NOW));

        assertThat(
                        commentLikes
                                .countByCommentIds(List.of(c.getCommunityCommentId()))
                                .get(0)
                                .getTotal())
                .isEqualTo(2L);
        assertThat(commentLikes.findLikedCommentIds(9L, List.of(c.getCommunityCommentId())))
                .containsExactly(c.getCommunityCommentId());
        assertThat(commentLikes.deleteLike(c.getCommunityCommentId(), 9L)).isEqualTo(1);
        assertThat(commentLikes.deleteLike(c.getCommunityCommentId(), 9L)).isZero();
    }

    @Test
    @DisplayName("신고: 같은 사람이 같은 글(또는 댓글)을 두 번 신고하면 DB가 막고, 글과 댓글은 따로 센다")
    void reportsUnique() {
        CommunityPostEntity p = post(7, "000660", "1");
        CommunityCommentEntity c = comment(p, 8);
        reports.saveAndFlush(
                CommunityReportEntity.ofPost(p.getCommunityPostId(), 9L, ReportReason.SPAM, NOW));
        reports.saveAndFlush(
                CommunityReportEntity.ofComment(
                        c.getCommunityCommentId(), 9L, ReportReason.SPAM, NOW));

        assertThat(reports.existsByReporterIdAndCommunityPostId(9L, p.getCommunityPostId()))
                .isTrue();
        assertThat(reports.existsByReporterIdAndCommunityCommentId(9L, c.getCommunityCommentId()))
                .isTrue();
        assertThat(reports.existsByReporterIdAndCommunityPostId(10L, p.getCommunityPostId()))
                .isFalse();
        assertThatThrownBy(
                        () ->
                                reports.saveAndFlush(
                                        CommunityReportEntity.ofPost(
                                                p.getCommunityPostId(),
                                                9L,
                                                ReportReason.ABUSE,
                                                NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("신고 처리 상태별 개수와 목록, 처리하면 PENDING에서 빠진다")
    void reportStatusQueries() {
        CommunityPostEntity p = post(7, "000660", "1");
        CommunityReportEntity r1 =
                reports.save(
                        CommunityReportEntity.ofPost(
                                p.getCommunityPostId(), 9L, ReportReason.SPAM, NOW));
        reports.save(
                CommunityReportEntity.ofPost(p.getCommunityPostId(), 10L, ReportReason.ABUSE, NOW));
        reports.flush();

        assertThat(
                        reports.countByCommunityPostIdAndStatus(
                                p.getCommunityPostId(), ReportStatus.PENDING))
                .isEqualTo(2);
        r1.accept(1L, NOW);
        reports.flush();
        assertThat(
                        reports.countByCommunityPostIdAndStatus(
                                p.getCommunityPostId(), ReportStatus.PENDING))
                .isEqualTo(1);
        assertThat(reports.findByStatusOrderByCommunityReportIdDesc(ReportStatus.ACCEPTED, TEN))
                .hasSize(1);
    }

    @Test
    @DisplayName("운영 DDL과 같은 제약: AI 댓글에 작성자를 넣거나 신고 대상을 둘 다 비우면 DB가 거부한다")
    void schemaChecks() {
        CommunityPostEntity p = post(7, "000660", "1");

        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO community_comment (community_post_id, author_type,"
                                                + " customer_id, content, status, created_at)"
                                                + " VALUES (?, 'AI', 7, 'x', 'VISIBLE', ?)",
                                        p.getCommunityPostId(),
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO community_report (reporter_id, reason, status,"
                                                + " created_at) VALUES (9, 'SPAM', 'PENDING', ?)",
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO community_post (customer_id, content, kiru_status,"
                                                + " status, created_at) VALUES (7, 'x', 'DONE', 'VISIBLE', ?)",
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("끼루 상태와 수정 시각이 저장되고 다시 읽힌다")
    void persistsKkiruAndEdit() {
        CommunityPostEntity p =
                posts.saveAndFlush(
                        CommunityPostEntity.write(
                                7L, "000660", "@끼루 질문", KkiruStatus.PENDING, NOW));
        p.moveKkiruStatus(KkiruStatus.NO_SOURCE);
        posts.flush();

        String stored =
                jdbc.queryForObject(
                        "SELECT kiru_status FROM community_post WHERE community_post_id = ?",
                        String.class,
                        p.getCommunityPostId());

        assertThat(stored).isEqualTo("NO_SOURCE");
    }
}
