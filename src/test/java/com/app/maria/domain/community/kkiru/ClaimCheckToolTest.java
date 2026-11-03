package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 주장 확인 도구: 확인할 주장을 어떻게 찾고, 공식 자료 도구를 어떻게 고르고 돌리는지. */
@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({JpaConfig.class, TransactionConfig.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaimCheckToolTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);

    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<EvidenceTool> registered = new ArrayList<>();
    private final List<KkiruQuestion> seen = new ArrayList<>();
    private ClaimCheckTool tool;

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
        registered.clear();
        seen.clear();
        @SuppressWarnings("unchecked")
        ObjectProvider<EvidenceTool> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> new ArrayList<>(registered).stream());
        tool = new ClaimCheckTool(provider, postRepository, commentRepository);
        registered.add(tool);
    }

    /** 단어가 맞으면 정해 둔 근거를 내는 가짜 공식 자료 도구. */
    private EvidenceTool official(
            String name, String word, List<Evidence> result, RuntimeException failure) {
        EvidenceTool fake =
                new EvidenceTool() {
                    public String name() {
                        return name;
                    }

                    public boolean isRelevant(KkiruQuestion q) {
                        return QuestionWords.containsAny(q, List.of(word));
                    }

                    public List<Evidence> collect(KkiruQuestion q) {
                        seen.add(q);
                        if (failure != null) {
                            throw failure;
                        }
                        return result;
                    }
                };
        registered.add(fake);
        return fake;
    }

    private static Evidence fact(String id, String text) {
        return new Evidence(id, text, null);
    }

    private CommunityPostEntity post(String ticker, String content) {
        return postRepository.saveAndFlush(
                CommunityPostEntity.write(1L, ticker, content, null, NOW));
    }

    private CommunityCommentEntity comment(CommunityPostEntity post, Long parent, String content) {
        return commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(), parent, 2L, content, null, NOW));
    }

    @Test
    @DisplayName("글 본문으로 부르면 그 글 자신이 주장이다: 주장(이용자 글 근거)을 맨 앞에, 공식 자료 근거를 뒤에 낸다")
    void postCallerIsTheClaim() {
        CommunityPostEntity post = post("000660", "@끼루 하이닉스 작년 주당 배당금이 5,000원이래요 사실이에요?");
        official("dividend", "배당", List.of(fact("dividend:1", "보통주 주당 현금배당금이 3,000원입니다.")), null);

        List<Evidence> evidence =
                tool.collect(
                        new KkiruQuestion(
                                post.getCommunityPostId(),
                                null,
                                "000660",
                                "하이닉스 작년 주당 배당금이 5,000원이래요 사실이에요?"));

        assertThat(evidence)
                .extracting(Evidence::id)
                .containsExactly("claim:post:" + post.getCommunityPostId(), "dividend:1");
        assertThat(evidence.get(0).userContent()).isTrue();
        assertThat(evidence.get(0).text()).isEqualTo("하이닉스 작년 주당 배당금이 5,000원이래요 사실이에요?");
        assertThat(evidence.get(1).userContent()).isFalse();
    }

    @Test
    @DisplayName("댓글로 불렀고 다른 댓글에 답한 것이면 그 댓글이 주장이다(주장 글을 질문으로 공식 도구를 고른다)")
    void replyTargetIsTheClaim() {
        CommunityPostEntity post = post("000660", "토론");
        CommunityCommentEntity claim = comment(post, null, "자사주 매입 공시가 났대요");
        CommunityCommentEntity caller = comment(post, claim.getCommunityCommentId(), "@끼루 사실이야?");
        official("treasury", "자사주", List.of(fact("treasury:1", "자기주식 취득 결정 공시가 있습니다.")), null);
        official("dividend", "배당", List.of(fact("dividend:1", "배당")), null);

        List<Evidence> evidence =
                tool.collect(
                        new KkiruQuestion(
                                post.getCommunityPostId(),
                                caller.getCommunityCommentId(),
                                "000660",
                                "사실이야?"));

        assertThat(evidence)
                .extracting(Evidence::id)
                .containsExactly("claim:comment:" + claim.getCommunityCommentId(), "treasury:1");
        // 도구는 질문("사실이야?")이 아니라 주장 글로 골라졌다
        assertThat(seen).extracting(KkiruQuestion::text).containsExactly("자사주 매입 공시가 났대요");
    }

    @Test
    @DisplayName("답할 대상 없이 댓글로 부르면 그 글 본문이 주장이다")
    void commentWithoutTargetUsesPost() {
        CommunityPostEntity post = post("000660", "하이닉스 유상증자 한대요");
        CommunityCommentEntity caller = comment(post, null, "@끼루 사실이야?");
        official("capital", "유상증자", List.of(fact("capital:1", "유상증자 결정 공시가 있습니다.")), null);

        List<Evidence> evidence =
                tool.collect(
                        new KkiruQuestion(
                                post.getCommunityPostId(),
                                caller.getCommunityCommentId(),
                                "000660",
                                "사실이야?"));

        assertThat(evidence)
                .extracting(Evidence::id)
                .containsExactly("claim:post:" + post.getCommunityPostId(), "capital:1");
    }

    @Test
    @DisplayName("답할 대상이 끼루 댓글·숨김·삭제된 댓글이면 확인하지 않는다(도구도 돌리지 않는다)")
    void invalidTargets() {
        CommunityPostEntity post = post("000660", "토론");
        official("dividend", "배당", List.of(fact("dividend:1", "배당")), null);
        CommunityCommentEntity ai =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofAi(
                                post.getCommunityPostId(), null, "배당 답", null, NOW));
        CommunityCommentEntity hidden = comment(post, null, "배당 얘기");
        hidden.hide();
        CommunityCommentEntity deleted = comment(post, null, "배당 얘기");
        deleted.markDeleted();
        commentRepository.flush();

        for (Long target :
                List.of(
                        ai.getCommunityCommentId(),
                        hidden.getCommunityCommentId(),
                        deleted.getCommunityCommentId())) {
            CommunityCommentEntity caller = comment(post, target, "@끼루 사실이야?");
            assertThat(
                            tool.collect(
                                    new KkiruQuestion(
                                            post.getCommunityPostId(),
                                            caller.getCommunityCommentId(),
                                            "000660",
                                            "사실이야?")))
                    .isEmpty();
        }
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("글이 숨겨졌으면 글 본문을 주장으로 쓰지 않는다")
    void hiddenPost() {
        CommunityPostEntity post = post("000660", "배당 얘기");
        CommunityCommentEntity caller = comment(post, null, "@끼루 사실이야?");
        post.hide();
        postRepository.flush();
        official("dividend", "배당", List.of(fact("dividend:1", "배당")), null);

        assertThat(
                        tool.collect(
                                new KkiruQuestion(
                                        post.getCommunityPostId(),
                                        caller.getCommunityCommentId(),
                                        "000660",
                                        "사실이야?")))
                .isEmpty();
    }

    @Test
    @DisplayName("주장 글에 맞는 공식 자료 도구가 없거나 근거가 없으면 빈 결과다(확인된 자료가 없어요)")
    void noOfficialEvidence() {
        CommunityPostEntity post = post("000660", "CEO가 바뀐대요");
        official("dividend", "배당", List.of(fact("dividend:1", "배당")), null);
        official("treasury", "자사주", List.of(), null);

        assertThat(
                        tool.collect(
                                new KkiruQuestion(
                                        post.getCommunityPostId(),
                                        null,
                                        "000660",
                                        "CEO가 바뀐대요 사실?")))
                .isEmpty(); // 맞는 도구 없음
        assertThat(
                        tool.collect(
                                new KkiruQuestion(
                                        post.getCommunityPostId(), null, "000660", "자사주 샀대요 사실?")))
                .isEmpty(); // 도구는 맞지만 근거 없음
    }

    @Test
    @DisplayName("주장 확인·토론 요약 도구와 종목이 필요 없는 도구는 공식 자료 도구로 고르지 않고, 최대 3개만 돌린다")
    void selectionRules() {
        CommunityPostEntity post = post("000660", "x");
        // 이 도구 자신(registered[0])은 이미 목록에 있다
        registered.add(new DiscussionTool(postRepository, commentRepository));
        official("a", "배당", List.of(fact("a:1", "a")), null);
        official("b", "배당", List.of(fact("b:1", "b")), null);
        official("c", "배당", List.of(fact("c:1", "c")), null);
        official("d", "배당", List.of(fact("d:1", "d")), null);

        List<Evidence> evidence =
                tool.collect(
                        new KkiruQuestion(
                                post.getCommunityPostId(), null, "000660", "배당 요약 정리 사실이야"));

        assertThat(evidence)
                .extracting(Evidence::id)
                .containsExactly("claim:post:" + post.getCommunityPostId(), "a:1", "b:1", "c:1");
    }

    @Test
    @DisplayName("공식 도구 하나가 실패해도 나머지 근거로 가고, 전부 실패하면 오류를 던진다")
    void partialAndTotalFailure() {
        CommunityPostEntity post = post("000660", "x");
        KkiruQuestion q = new KkiruQuestion(post.getCommunityPostId(), null, "000660", "배당 사실이야");
        official("ok", "배당", List.of(fact("ok:1", "정상")), null);
        official("bad", "배당", null, new AppException(ErrorType.OPENDART_UNAVAILABLE));

        assertThat(tool.collect(q)).extracting(Evidence::id).contains("ok:1");

        registered.removeIf(t -> t.name().equals("ok"));
        assertThatThrownBy(() -> tool.collect(q))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.OPENDART_UNAVAILABLE));
    }

    @Test
    @DisplayName("주장 글은 @끼루·제어 문자를 지우고 300자로 자른 근거로 넣는다")
    void claimTextIsCleaned() {
        CommunityPostEntity post = post("000660", "x");
        official("dividend", "배당", List.of(fact("dividend:1", "배당")), null);

        List<Evidence> evidence =
                tool.collect(
                        new KkiruQuestion(
                                post.getCommunityPostId(),
                                null,
                                "000660",
                                "@끼루 배당\n줄까요 " + "가".repeat(400) + " 사실?"));

        String claim = evidence.get(0).text();
        assertThat(claim).doesNotContain("@끼루").doesNotContain("\n");
        assertThat(claim.codePointCount(0, claim.length()))
                .isLessThanOrEqualTo(ClaimCheckTool.CLAIM_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "이거 사실이야?",
                "팩트체크 해줘",
                "진짜예요?",
                "이거 맞아?",
                "맞는지 확인해줘",
                "루머 아니에요?",
                "소문 믿어도 돼요"
            })
    @DisplayName("사실·팩트·진짜·맞아·맞는지·루머·소문·확인해·믿어도가 있으면 후보다")
    void relevant(String text) {
        assertThat(tool.isRelevant(new KkiruQuestion(1L, null, "000660", text))).isTrue();
        assertThat(tool.isRelevant(new KkiruQuestion(1L, null, "000660", "배당 어땠어요"))).isFalse();
    }
}
