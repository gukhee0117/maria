package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.domain.notification.service.CustomerNotificationService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
@Import({
    JpaConfig.class,
    TransactionConfig.class,
    KkiruReplyService.class,
    KkiruReplyWriter.class,
    KkiruReplyComposer.class,
    KkiruReplyServiceTest.JsonBeans.class
})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KkiruReplyServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    @TestConfiguration
    static class JsonBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private KkiruReplyService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private CustomerNotificationService notificationService;

    @MockitoBean private EvidenceCollector collector;
    @MockitoBean private KkiruAnswerWriter answerWriter;
    @MockitoBean private BusinessClockService clock;

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
        when(clock.now()).thenReturn(NOW);
    }

    private CommunityPostEntity pendingPost() {
        CommunityPostEntity post =
                CommunityPostEntity.write(
                        1L, "000660", "@끼루 증설 공시가 있나요?", KkiruStatus.PENDING, NOW);
        return postRepository.saveAndFlush(post);
    }

    private CommunityCommentEntity pendingComment(CommunityPostEntity post) {
        return commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(),
                        null,
                        2L,
                        "@끼루 위험등급은요?",
                        KkiruStatus.PENDING,
                        NOW));
    }

    private void stubCollected() {
        when(collector.collect(any()))
                .thenReturn(
                        new EvidenceCollector.Collected(
                                List.of(new Evidence("risk:000660", "위험등급 2등급입니다.", null)),
                                List.of("risk"),
                                0));
    }

    private void stubAnswer(KkiruAnswer answer) {
        stubCollected();
        when(answerWriter.write(any(), any(), anyBoolean()))
                .thenReturn(Optional.of(new KkiruAnswerWriter.Written(answer, false)));
    }

    private void commitAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<CommunityCommentEntity> aiComments() {
        return commentRepository.findAll().stream()
                .filter(c -> c.getAuthorType() == AuthorType.AI)
                .toList();
    }

    @Test
    @DisplayName("글 본문에서 부르면 parent 없는 끼루 댓글을 달고 글의 상태가 ANSWERED가 된다")
    void answerPostCall() {
        CommunityPostEntity post = pendingPost();
        stubAnswer(KkiruTestData.twoSentenceAnswer());

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        List<CommunityCommentEntity> ai = aiComments();
        assertThat(ai).hasSize(1);
        assertThat(ai.get(0).getParentCommentId()).isNull();
        assertThat(ai.get(0).getContent()).isEqualTo("최근 30일 동안 공시가 3건 올라왔어요. 자체 산정 위험등급은 2등급이에요.");
        assertThat(ai.get(0).getSourcesJson()).contains("DART:1").contains("RISK:7");
        assertThat(ai.get(0).getCreatedAt()).isEqualTo(NOW);
        org.mockito.Mockito.verifyNoInteractions(notificationService);
        CommunityPostEntity reloaded =
                postRepository.findById(post.getCommunityPostId()).orElseThrow();
        assertThat(reloaded.getKkiruStatus()).isEqualTo(KkiruStatus.ANSWERED);
        assertThat(reloaded.canEdit()).isFalse();
    }

    @Test
    @DisplayName("댓글에서 부르면 그 댓글 id를 parent로 한 끼루 댓글을 달고 댓글의 상태가 ANSWERED가 된다")
    void answerCommentCall() {
        CommunityPostEntity post = pendingPost();
        post.moveKkiruStatus(KkiruStatus.NO_SOURCE);
        CommunityCommentEntity caller = pendingComment(post);
        stubAnswer(KkiruTestData.riskAnswer());

        service.reply(post.getCommunityPostId(), caller.getCommunityCommentId());

        commitAndClear();
        List<CommunityCommentEntity> ai = aiComments();
        assertThat(ai).hasSize(1);
        assertThat(ai.get(0).getParentCommentId()).isEqualTo(caller.getCommunityCommentId());
        org.mockito.Mockito.verifyNoInteractions(notificationService);
        assertThat(
                        commentRepository
                                .findById(caller.getCommunityCommentId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.ANSWERED);
    }

    @Test
    @DisplayName("도구가 근거를 하나도 못 찾으면 NO_SOURCE로 끝나고 답글은 없다")
    void noSource() {
        CommunityPostEntity post = pendingPost();
        when(collector.collect(any()))
                .thenReturn(new EvidenceCollector.Collected(List.of(), List.of("risk"), 0));

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(
                        postRepository
                                .findById(post.getCommunityPostId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.NO_SOURCE);
    }

    @Test
    @DisplayName("답 쓰기가 예외를 던지면 FAILED로 끝나고 답글은 없다")
    void panelFailure() {
        CommunityPostEntity post = pendingPost();
        stubCollected();
        when(answerWriter.write(any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("LLM 오류"));

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(
                        postRepository
                                .findById(post.getCommunityPostId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("500자에 들어가지 않는 답이면 FAILED로 끝난다")
    void composeImpossible() {
        CommunityPostEntity post = pendingPost();
        stubAnswer(KkiruTestData.answer("가".repeat(700), "A", "근거"));

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(
                        postRepository
                                .findById(post.getCommunityPostId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("PENDING이 아닌 호출(범위 밖·이미 답함·호출 없음)은 도구를 부르지 않고 아무것도 바꾸지 않는다")
    void notPendingIsNoOp() {
        CommunityPostEntity outOfScope =
                postRepository.saveAndFlush(
                        CommunityPostEntity.write(
                                1L, "000660", "@끼루 사도 돼요?", KkiruStatus.OUT_OF_SCOPE, NOW));
        CommunityPostEntity none =
                postRepository.saveAndFlush(
                        CommunityPostEntity.write(1L, "000660", "그냥 글", null, NOW));

        service.reply(outOfScope.getCommunityPostId(), null);
        service.reply(none.getCommunityPostId(), null);
        service.reply(999_999L, null);
        service.reply(none.getCommunityPostId(), 999_999L);

        verify(collector, never()).collect(any());
        commitAndClear();
        assertThat(aiComments()).isEmpty();
    }

    @Test
    @DisplayName("같은 호출을 두 번 처리해도 끼루 댓글은 하나만 생긴다")
    void idempotent() {
        CommunityPostEntity post = pendingPost();
        stubAnswer(KkiruTestData.riskAnswer());

        service.reply(post.getCommunityPostId(), null);
        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).hasSize(1);
    }

    @Test
    @DisplayName("이미 끼루 댓글이 있는데 상태만 PENDING으로 남은 호출은 댓글을 또 달지 않고 ANSWERED로 끝낸다")
    void existingAnswerIsNotDuplicated() {
        CommunityPostEntity post = pendingPost();
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofAi(post.getCommunityPostId(), null, "먼저 달린 답", null, NOW));
        stubAnswer(KkiruTestData.riskAnswer());

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).hasSize(1);
        assertThat(aiComments().get(0).getContent()).isEqualTo("먼저 달린 답");
        assertThat(
                        postRepository
                                .findById(post.getCommunityPostId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.ANSWERED);
    }

    @Test
    @DisplayName("답을 만드는 사이 다른 처리가 호출을 끝냈으면 저장하지 않는다")
    void statusChangedWhileWorking() {
        CommunityPostEntity post = pendingPost();
        stubCollected();
        when(answerWriter.write(any(), any(), anyBoolean()))
                .thenAnswer(
                        invocation -> {
                            // 다른 처리가 DB를 바꾼 것처럼: 반영한 뒤 DB를 직접 고치고 영속성 컨텍스트를 비운다
                            entityManager.flush();
                            jdbc.update(
                                    "UPDATE community_post SET kiru_status = 'FAILED' WHERE community_post_id = ?",
                                    post.getCommunityPostId());
                            entityManager.clear();
                            return Optional.of(
                                    new KkiruAnswerWriter.Written(
                                            KkiruTestData.riskAnswer(), false));
                        });

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(
                        postRepository
                                .findById(post.getCommunityPostId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("답을 만드는 사이 호출한 글이 숨겨지거나 삭제되면 답글을 달지 않고 FAILED로 끝낸다")
    void callerHiddenWhileWorking() {
        CommunityPostEntity post = pendingPost();
        stubCollected();
        when(answerWriter.write(any(), any(), anyBoolean()))
                .thenAnswer(
                        invocation -> {
                            entityManager.flush();
                            jdbc.update(
                                    "UPDATE community_post SET status = 'DELETED' WHERE community_post_id = ?",
                                    post.getCommunityPostId());
                            entityManager.clear();
                            return Optional.of(
                                    new KkiruAnswerWriter.Written(
                                            KkiruTestData.riskAnswer(), false));
                        });

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        CommunityPostEntity reloaded =
                postRepository.findById(post.getCommunityPostId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(CommunityStatus.DELETED);
        assertThat(reloaded.getKkiruStatus()).isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("댓글 호출이 끝나기 전에 그 글이 숨겨지면 답글을 달지 않고 FAILED로 끝낸다")
    void parentPostHiddenWhileWorking() {
        CommunityPostEntity post = pendingPost();
        post.moveKkiruStatus(KkiruStatus.NO_SOURCE);
        CommunityCommentEntity caller = pendingComment(post);
        stubCollected();
        when(answerWriter.write(any(), any(), anyBoolean()))
                .thenAnswer(
                        invocation -> {
                            entityManager.flush();
                            jdbc.update(
                                    "UPDATE community_post SET status = 'HIDDEN' WHERE community_post_id = ?",
                                    post.getCommunityPostId());
                            entityManager.clear();
                            return Optional.of(
                                    new KkiruAnswerWriter.Written(
                                            KkiruTestData.riskAnswer(), false));
                        });

        service.reply(post.getCommunityPostId(), caller.getCommunityCommentId());

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(
                        commentRepository
                                .findById(caller.getCommunityCommentId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.FAILED);
    }

    private KkiruStatus postStatus(CommunityPostEntity post) {
        return postRepository.findById(post.getCommunityPostId()).orElseThrow().getKkiruStatus();
    }

    @Test
    @DisplayName("모든 도구가 오류면 FAILED로 끝나고 LLM은 부르지 않는다")
    void allToolsFailed() {
        CommunityPostEntity post = pendingPost();
        when(collector.collect(any()))
                .thenReturn(new EvidenceCollector.Collected(List.of(), List.of("risk"), 1));

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(postStatus(post)).isEqualTo(KkiruStatus.FAILED);
        verify(answerWriter, never()).write(any(), any());
    }

    @Test
    @DisplayName("오류 난 도구가 있고 나머지는 근거가 없으면 NO_SOURCE가 아니라 FAILED(다시 물을 수 있게)")
    void partialErrorWithoutEvidence() {
        CommunityPostEntity post = pendingPost();
        when(collector.collect(any()))
                .thenReturn(new EvidenceCollector.Collected(List.of(), List.of("a", "b"), 1));

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(postStatus(post)).isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("답을 못 만들면(LLM·템플릿 모두 실패) FAILED로 끝난다")
    void writerEmpty() {
        CommunityPostEntity post = pendingPost();
        stubCollected();
        when(answerWriter.write(any(), any(), anyBoolean())).thenReturn(Optional.empty());

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(postStatus(post)).isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("템플릿 답(fallback)도 ANSWERED로 저장된다")
    void fallbackAnswerIsAnswered() {
        CommunityPostEntity post = pendingPost();
        stubCollected();
        when(answerWriter.write(any(), any(), anyBoolean()))
                .thenReturn(
                        Optional.of(
                                new KkiruAnswerWriter.Written(KkiruTestData.riskAnswer(), true)));

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).hasSize(1);
        assertThat(postStatus(post)).isEqualTo(KkiruStatus.ANSWERED);
    }

    @Test
    @DisplayName("도구와 LLM에는 @끼루를 뺀 질문과 글의 종목코드가 간다")
    void refinedQuestionAndTicker() {
        CommunityPostEntity post = pendingPost();
        stubAnswer(KkiruTestData.riskAnswer());

        service.reply(post.getCommunityPostId(), null);

        ArgumentCaptor<KkiruQuestion> captor = ArgumentCaptor.forClass(KkiruQuestion.class);
        verify(collector).collect(captor.capture());
        assertThat(captor.getValue().text()).isEqualTo("증설 공시가 있나요?");
        assertThat(captor.getValue().ticker()).isEqualTo("000660");
        verify(answerWriter).write(eq("증설 공시가 있나요?"), any(), eq(true));
    }

    @Test
    @DisplayName("기본 묶음(질문과 맞는 단어를 못 찾음)의 근거로는 LLM이 답할 수 없다고 하면 NO_SOURCE로 끝나고 답글은 없다")
    void declinedOnDefaultBundleIsNoSource() {
        CommunityPostEntity post = pendingPost();
        when(collector.collect(any()))
                .thenReturn(
                        new EvidenceCollector.Collected(
                                List.of(new Evidence("disclosure:1", "최근 30일 공시는 3건입니다.", null)),
                                List.of("disclosure", "risk"),
                                0,
                                true));
        when(answerWriter.write(any(), any(), eq(false)))
                .thenReturn(
                        Optional.of(
                                new KkiruAnswerWriter.Written(
                                        null, KkiruAnswerWriter.Kind.DECLINED)));

        service.reply(post.getCommunityPostId(), null);

        commitAndClear();
        assertThat(aiComments()).isEmpty();
        assertThat(postStatus(post)).isEqualTo(KkiruStatus.NO_SOURCE);
    }

    @Test
    @DisplayName("키워드로 고른 도구의 근거는 질문에 맞는 근거로 취급해 true를 넘긴다(기본 묶음만 false)")
    void matchedFlagIsPassed() {
        CommunityPostEntity post = pendingPost();
        stubAnswer(KkiruTestData.riskAnswer());

        service.reply(post.getCommunityPostId(), null);

        verify(answerWriter).write(any(), any(), eq(true));
    }
}
