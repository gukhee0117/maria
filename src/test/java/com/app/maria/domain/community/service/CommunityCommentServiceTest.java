package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.request.CommentWriteRequestDTO;
import com.app.maria.domain.community.dto.response.CommentPageResponseDTO;
import com.app.maria.domain.community.dto.response.CommentResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityCommentLikeEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.repository.CommunityCommentLikeRepository;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.AuthorType;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.domain.notification.service.CustomerNotificationService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
    CommunityCommentServiceImpl.class,
    CommunityCommentAssembler.class,
    CommunityTextGuard.class,
    KkiruCallPlanner.class,
    KkiruCallGate.class,
    KkiruQuestionClassifier.class
})
@RecordApplicationEvents
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityCommentServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired private CommunityCommentService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private CommunityCommentLikeRepository likeRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;
    @MockitoBean private CustomerNotificationService notificationService;

    @MockitoBean private BusinessClockService clock;

    private long postAuthor;
    private long other;
    private long postId;

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
        jdbc.execute("DELETE FROM community_comment");
        jdbc.execute("DELETE FROM community_post");
        jdbc.execute("DELETE FROM customer");
        when(clock.now()).thenReturn(NOW);
        postAuthor = insertCustomer("글쓴이", "ACTIVE", false);
        other = insertCustomer("장기보유러", "NEUTRAL", false);
        postId = savePost(postAuthor, "토론 글").getCommunityPostId();
    }

    private long insertCustomer(String nickname, String investorType, boolean publicType) {
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash, nickname,"
                        + " investor_type_public) VALUES (?, ?, ?, ?, ?, ?)",
                "홍길동",
                "1990-01-01",
                investorType,
                "hash-" + System.nanoTime(),
                nickname,
                publicType);
        return jdbc.queryForObject("SELECT MAX(customer_id) FROM customer", Long.class);
    }

    private CommunityPostEntity savePost(long customerId, String content) {
        return postRepository.saveAndFlush(
                CommunityPostEntity.write(customerId, "000660", content, null, NOW));
    }

    private CommentResponseDTO write(long customerId, String content, Long replyTo) {
        return service.write(customerId, postId, new CommentWriteRequestDTO(content, replyTo));
    }

    private CommunityCommentEntity savedComment(long customerId, String content) {
        return commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(postId, null, customerId, content, null, NOW));
    }

    private static void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private long commentCount() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM community_comment", Long.class);
    }

    // ---------- 쓰기 ----------

    @Test
    @DisplayName("글에 바로 댓글을 달면 저장되고 글쓴이가 쓴 댓글은 글쓴이 표시가 켜진다")
    void write() {
        CommentResponseDTO byOther = write(other, "  저도 봤어요  ", null);
        CommentResponseDTO byAuthor = write(postAuthor, "작성자 댓글", null);

        assertThat(byOther.content()).isEqualTo("저도 봤어요");
        assertThat(byOther.authorNickname()).isEqualTo("장기보유러");
        assertThat(byOther.authorType()).isEqualTo(AuthorType.CUSTOMER);
        assertThat(byOther.mine()).isTrue();
        assertThat(byOther.postAuthor()).isFalse();
        assertThat(byOther.replyToCommentId()).isNull();
        assertThat(byOther.replyToNickname()).isNull();
        assertThat(byOther.createdAt()).isEqualTo(NOW);
        org.mockito.Mockito.verifyNoInteractions(notificationService);
        assertThat(byAuthor.postAuthor()).isTrue();
    }

    @Test
    @DisplayName("본문·금칙어가 잘못이면 거부하고 저장하지 않는다")
    void writeValidation() {
        assertError(() -> write(other, "  ", null), ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(() -> write(other, "가".repeat(501), null), ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(() -> write(other, "이 종목 산 놈들 다 개새끼", null), ErrorType.COMMUNITY_TEXT_BANNED);
        assertThat(commentCount()).isZero();
        assertThat(write(other, "문의는 010-1234-5678", null).content())
                .isEqualTo("문의는 010-****-****");
    }

    @Test
    @DisplayName("없는 글에는 NOT_FOUND, 숨김·삭제된 글에는 POST_NOT_VISIBLE이고 저장하지 않는다")
    void writeOnUnavailablePost() {
        CommunityPostEntity hidden = savePost(postAuthor, "숨김");
        hidden.hide();
        CommunityPostEntity deleted = savePost(postAuthor, "삭제");
        deleted.markDeleted();
        postRepository.flush();

        assertError(
                () -> service.write(other, 999_999L, new CommentWriteRequestDTO("x", null)),
                ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertError(
                () ->
                        service.write(
                                other,
                                hidden.getCommunityPostId(),
                                new CommentWriteRequestDTO("x", null)),
                ErrorType.COMMUNITY_POST_NOT_VISIBLE);
        assertError(
                () ->
                        service.write(
                                other,
                                deleted.getCommunityPostId(),
                                new CommentWriteRequestDTO("x", null)),
                ErrorType.COMMUNITY_POST_NOT_VISIBLE);
        assertThat(commentCount()).isZero();
    }

    // ---------- 답하는 대상 ----------

    @Test
    @DisplayName("답하면 대상 댓글 id가 저장되고, @닉네임은 대상 작성자의 현재 닉네임으로 내려온다")
    void replyTarget() {
        CommunityCommentEntity target = savedComment(other, "두 배 정도였어요");

        CommentResponseDTO reply =
                write(postAuthor, "@아무이름 두 배면 꽤 많네요", target.getCommunityCommentId());

        assertThat(reply.replyToCommentId()).isEqualTo(target.getCommunityCommentId());
        assertThat(reply.replyToNickname()).isEqualTo("장기보유러");
        // 본문의 @글자는 대상을 정하지 않는다: 대상 닉네임은 항상 DB의 현재 값으로 내려온다
        jdbc.update("UPDATE customer SET nickname = '개명했어요' WHERE customer_id = ?", other);
        entityManager.clear();
        CommentResponseDTO listed = service.list(postAuthor, postId, null, 10).items().get(1);
        assertThat(listed.replyToNickname()).isEqualTo("개명했어요");
        assertThat(listed.content()).contains("@아무이름");
    }

    @Test
    @DisplayName("답에 다시 답해도 중첩되지 않고 방금 답한 댓글이 대상으로 남는다")
    void replyToReplyIsFlat() {
        CommunityCommentEntity first = savedComment(other, "첫 댓글");
        CommentResponseDTO second = write(postAuthor, "답", first.getCommunityCommentId());
        CommentResponseDTO third = write(other, "답의 답", second.id());

        assertThat(third.replyToCommentId()).isEqualTo(second.id());
        org.mockito.Mockito.verifyNoInteractions(notificationService);
        org.mockito.Mockito.verifyNoInteractions(notificationService);
        assertThat(service.list(other, postId, null, 10).items()).hasSize(3);
    }

    @Test
    @DisplayName("다른 글의 댓글·숨김·삭제·없는 댓글에는 답할 수 없다")
    void invalidReplyTargets() {
        CommunityPostEntity otherPost = savePost(postAuthor, "다른 글");
        CommunityCommentEntity onOtherPost =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofCustomer(
                                otherPost.getCommunityPostId(), null, other, "다른 글 댓글", null, NOW));
        CommunityCommentEntity hidden = savedComment(other, "숨김");
        hidden.hide();
        CommunityCommentEntity deleted = savedComment(other, "삭제");
        deleted.markDeleted();
        commentRepository.flush();

        assertError(
                () -> write(postAuthor, "x", onOtherPost.getCommunityCommentId()),
                ErrorType.COMMUNITY_REPLY_TARGET_INVALID);
        assertError(
                () -> write(postAuthor, "x", hidden.getCommunityCommentId()),
                ErrorType.COMMUNITY_REPLY_TARGET_INVALID);
        assertError(
                () -> write(postAuthor, "x", deleted.getCommunityCommentId()),
                ErrorType.COMMUNITY_REPLY_TARGET_INVALID);
        assertError(
                () -> write(postAuthor, "x", 999_999L), ErrorType.COMMUNITY_REPLY_TARGET_INVALID);
        assertThat(commentCount()).isEqualTo(3); // 준비한 3개뿐
    }

    @Test
    @DisplayName("닉네임이 없는 사람의 댓글에는 답할 수 없다(언급할 수 없으므로)")
    void cannotReplyToNicknameless() {
        long nobody = insertCustomer(null, "ACTIVE", false);
        CommunityCommentEntity target = savedComment(nobody, "닉네임 없는 댓글");

        assertError(
                () -> write(postAuthor, "x", target.getCommunityCommentId()),
                ErrorType.COMMUNITY_REPLY_TARGET_INVALID);
        assertThat(commentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("답하던 대상 댓글이 나중에 삭제되면 대상 닉네임은 비어서 내려온다")
    void targetDeletedLater() {
        CommunityCommentEntity target = savedComment(other, "곧 지울 댓글");
        write(postAuthor, "답", target.getCommunityCommentId());
        target.markDeleted();
        commentRepository.flush();

        List<CommentResponseDTO> items = service.list(postAuthor, postId, null, 10).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).replyToCommentId()).isEqualTo(target.getCommunityCommentId());
        assertThat(items.get(0).replyToNickname()).isNull();
    }

    // ---------- 끼루 ----------

    @Test
    @DisplayName("댓글에 @끼루가 있으면 PENDING으로 저장하고 호출한 댓글 id로 이벤트를 낸다")
    void kkiruCallOnComment() {
        CommentResponseDTO call = write(other, "@끼루 증설 공시가 있나요?", null);

        assertThat(call.kkiruStatus()).isEqualTo(KkiruStatus.PENDING);
        List<KkiruCallRequestedEvent> published =
                events.stream(KkiruCallRequestedEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).postId()).isEqualTo(postId);
        assertThat(published.get(0).commentId()).isEqualTo(call.id());
    }

    @Test
    @DisplayName("매수·전망 질문은 OUT_OF_SCOPE로 끝나고 이벤트가 없다")
    void kkiruOutOfScope() {
        assertThat(write(other, "@끼루 지금 사도 돼요?", null).kkiruStatus())
                .isEqualTo(KkiruStatus.OUT_OF_SCOPE);
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("끼루 댓글에 다시 @끼루로 물으면 후속 질문이라 FOLLOW_UP이고 이벤트가 없다")
    void kkiruFollowUp() {
        CommunityCommentEntity ai =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofAi(postId, null, "끼루 답변", null, NOW));

        CommentResponseDTO followUp = write(other, "@끼루 그럼 위험등급은요?", ai.getCommunityCommentId());

        assertThat(followUp.kkiruStatus()).isEqualTo(KkiruStatus.FOLLOW_UP);
        assertThat(followUp.replyToNickname()).isEqualTo("끼루 AI");
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("글의 호출 한도를 다 써도 끼루 댓글에 대한 후속 질문은 한도 오류 없이 FOLLOW_UP으로 저장된다")
    void kkiruFollowUpIgnoresLimit() {
        CommunityCommentEntity ai =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofAi(postId, null, "끼루 답변", null, NOW));
        postRepository.findById(postId).orElseThrow().moveKkiruStatus(KkiruStatus.OUT_OF_SCOPE);
        postRepository.flush();
        write(other, "@끼루 질문 1", null);
        write(other, "@끼루 질문 2", null);
        assertError(() -> write(other, "@끼루 질문 3", null), ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED);

        CommentResponseDTO followUp = write(other, "@끼루 그럼 위험등급은요?", ai.getCommunityCommentId());

        assertThat(followUp.kkiruStatus()).isEqualTo(KkiruStatus.FOLLOW_UP);
    }

    @Test
    @DisplayName("후속 질문은 글 한도를 쓰지 않는다: 질문 1번과 후속 질문 2번 뒤에도 새 질문을 부를 수 있다")
    void followUpDoesNotConsumePostLimit() {
        write(other, "@끼루 질문 1", null);
        CommunityCommentEntity ai =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofAi(postId, null, "끼루 답변", null, NOW));
        write(other, "@끼루 그럼 위험등급은요?", ai.getCommunityCommentId());
        write(other, "@끼루 그럼 배당은요?", ai.getCommunityCommentId());

        CommentResponseDTO next = write(other, "@끼루 질문 2", null);

        assertThat(next.kkiruStatus()).isEqualTo(KkiruStatus.PENDING);
    }

    @Test
    @DisplayName("글 하나에서 끼루를 부르는 횟수는 3번까지다(글 본문 호출 포함)")
    void kkiruPerPostLimit() {
        postRepository
                .findById(postId)
                .orElseThrow()
                .moveKkiruStatus(KkiruStatus.OUT_OF_SCOPE); // 먼저 NULL→OUT_OF_SCOPE: 글 본문 호출 1번으로 센다
        postRepository.flush();
        write(other, "@끼루 질문 1", null);
        write(other, "@끼루 질문 2", null);

        assertError(() -> write(other, "@끼루 질문 3", null), ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED);
        assertThat(commentCount()).isEqualTo(2);
        write(other, "끼루 없는 댓글은 계속 달 수 있다", null);
    }

    // ---------- 삭제 ----------

    @Test
    @DisplayName("신고로 숨겨진 댓글은 작성자가 지울 수 없다")
    void cannotDeleteHiddenComment() {
        CommunityCommentEntity mine = savedComment(other, "신고당한 댓글");
        mine.hide();
        entityManager.flush();

        assertError(
                () -> service.delete(other, mine.getCommunityCommentId()),
                ErrorType.COMMUNITY_REPORT_TARGET_UNAVAILABLE);
        assertThat(mine.getStatus()).isEqualTo(CommunityStatus.HIDDEN);
    }

    @Test
    @DisplayName("본인 댓글은 소프트 삭제하고 남의 댓글·끼루 댓글은 지울 수 없다")
    void delete() {
        CommunityCommentEntity mine = savedComment(other, "내 댓글");
        CommunityCommentEntity ai =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofAi(postId, null, "끼루 답변", null, NOW));

        assertError(
                () -> service.delete(postAuthor, mine.getCommunityCommentId()),
                ErrorType.COMMUNITY_NOT_AUTHOR);
        assertError(
                () -> service.delete(other, ai.getCommunityCommentId()),
                ErrorType.COMMUNITY_NOT_AUTHOR);
        assertError(
                () -> service.delete(postAuthor, ai.getCommunityCommentId()),
                ErrorType.COMMUNITY_NOT_AUTHOR);
        service.delete(other, mine.getCommunityCommentId());

        entityManager.flush();
        entityManager.clear();
        assertThat(
                        commentRepository
                                .findById(mine.getCommunityCommentId())
                                .orElseThrow()
                                .getStatus())
                .isEqualTo(CommunityStatus.DELETED);
        assertThat(commentRepository.findById(ai.getCommunityCommentId()).orElseThrow().getStatus())
                .isEqualTo(CommunityStatus.VISIBLE);
        assertError(
                () -> service.delete(other, mine.getCommunityCommentId()),
                ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
        assertError(() -> service.delete(other, 999_999L), ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
    }

    // ---------- 목록 ----------

    @Test
    @DisplayName("댓글 목록은 오래된 순 커서로 겹침·누락 없이 이어지고 삭제된 댓글은 빠진다")
    void listPaging() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(savedComment(other, "댓글 " + i).getCommunityCommentId());
        }
        CommunityCommentEntity gone = savedComment(other, "지울 댓글");
        gone.markDeleted();
        commentRepository.flush();

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            CommentPageResponseDTO page = service.list(postAuthor, postId, cursor, 2);
            page.items().forEach(item -> seen.add(item.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    @DisplayName("딱 size개만 남았을 때는 다음 쪽이 없다")
    void listExactPage() {
        savedComment(other, "1");
        savedComment(other, "2");

        CommentPageResponseDTO page = service.list(postAuthor, postId, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("신고로 숨겨진 글의 댓글 목록은 볼 수 없다")
    void cannotListCommentsOfHiddenPost() {
        savedComment(other, "숨김 글에 달린 댓글");
        postRepository.findById(postId).orElseThrow().hide();
        entityManager.flush();

        assertError(() -> service.list(other, postId, null, 10), ErrorType.COMMUNITY_POST_HIDDEN);
    }

    @Test
    @DisplayName("숨김 댓글은 본문과 근거 대신 안내 문구가 나간다")
    void hiddenCommentIsMasked() {
        CommunityCommentEntity ai =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofAi(
                                postId, null, "비밀 답변 본문", "[{\"type\":\"DART\"}]", NOW));
        ai.hide();
        commentRepository.flush();

        CommentResponseDTO hidden = service.list(other, postId, null, 10).items().get(0);

        assertThat(hidden.content()).isEqualTo(CommunityCommentAssembler.HIDDEN_TEXT);
        assertThat(hidden.sourcesJson()).isNull();
        assertThat(hidden.status()).isEqualTo(CommunityStatus.HIDDEN);
    }

    @Test
    @DisplayName("끼루 댓글은 AI 표시와 근거를 갖고 작성자 정보가 없다")
    void aiCommentShape() {
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofAi(
                        postId, null, "공시 기준으로…", "[{\"type\":\"DART\"}]", NOW));

        CommentResponseDTO ai = service.list(other, postId, null, 10).items().get(0);

        assertThat(ai.authorType()).isEqualTo(AuthorType.AI);
        assertThat(ai.authorNickname()).isEqualTo(CommunityCommentAssembler.AI_NICKNAME);
        assertThat(ai.authorInvestorType()).isNull();
        assertThat(ai.mine()).isFalse();
        assertThat(ai.postAuthor()).isFalse();
        assertThat(ai.sourcesJson()).contains("DART");
    }

    @Test
    @DisplayName("좋아요 수·내가 눌렀는지·성향 공개 동의가 정확히 들어간다")
    void listAssemblesLikesAndInvestorType() {
        long publicUser = insertCustomer("성향공개", "AGGRESSIVE", true);
        long privateUser = insertCustomer("성향비공개", "AGGRESSIVE", false);
        CommunityCommentEntity c1 = savedComment(publicUser, "공개");
        savedComment(privateUser, "비공개");
        likeRepository.saveAndFlush(
                CommunityCommentLikeEntity.of(c1.getCommunityCommentId(), other, NOW));
        likeRepository.saveAndFlush(
                CommunityCommentLikeEntity.of(c1.getCommunityCommentId(), postAuthor, NOW));

        List<CommentResponseDTO> items = service.list(other, postId, null, 10).items();

        assertThat(items.get(0).likeCount()).isEqualTo(2);
        assertThat(items.get(0).likedByMe()).isTrue();
        assertThat(items.get(0).authorInvestorType()).isEqualTo("AGGRESSIVE");
        assertThat(items.get(1).likeCount()).isZero();
        assertThat(items.get(1).likedByMe()).isFalse();
        assertThat(items.get(1).authorInvestorType()).isNull();
    }

    @Test
    @DisplayName("없는 글·댓글 없이 삭제된 글의 댓글 목록은 NOT_FOUND이고, 댓글이 남은 삭제 글은 목록이 보인다")
    void listOnDeletedPost() {
        CommunityPostEntity gone = savePost(postAuthor, "혼자 삭제");
        gone.markDeleted();
        CommunityPostEntity withComment = savePost(postAuthor, "댓글 남은 삭제");
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        withComment.getCommunityPostId(), null, other, "남은 댓글", null, NOW));
        withComment.markDeleted();
        postRepository.flush();

        assertError(
                () -> service.list(other, 999_999L, null, 10), ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertError(
                () -> service.list(other, gone.getCommunityPostId(), null, 10),
                ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertThat(service.list(other, withComment.getCommunityPostId(), null, 10).items())
                .hasSize(1);
    }

    // ---------- 끼루 다시 묻기 ----------

    private CommunityCommentEntity commentWith(long customerId, KkiruStatus status) {
        return commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(postId, null, customerId, "@끼루 질문", status, NOW));
    }

    @Test
    @DisplayName("다시 묻기: 내 FAILED 댓글이 PENDING이 되고 (글, 댓글) 호출 이벤트가 나간다")
    void retryKkiru() {
        CommunityCommentEntity failed = commentWith(other, KkiruStatus.FAILED);
        long before = commentRepository.count();

        CommentResponseDTO retried = service.retryKkiru(other, failed.getCommunityCommentId());

        assertThat(retried.kkiruStatus()).isEqualTo(KkiruStatus.PENDING);
        assertThat(commentRepository.count()).isEqualTo(before);
        List<KkiruCallRequestedEvent> published =
                events.stream(KkiruCallRequestedEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).postId()).isEqualTo(postId);
        assertThat(published.get(0).commentId()).isEqualTo(failed.getCommunityCommentId());
    }

    @Test
    @DisplayName("다시 묻기는 댓글 작성자 본인만(끼루 댓글·남의 댓글 불가), 없는 댓글은 404")
    void retryKkiruAuthorAndNotFound() {
        CommunityCommentEntity failed = commentWith(other, KkiruStatus.FAILED);
        CommunityCommentEntity ai =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofAi(postId, null, "끼루 답", null, NOW));

        assertError(
                () -> service.retryKkiru(postAuthor, failed.getCommunityCommentId()),
                ErrorType.COMMUNITY_NOT_AUTHOR);
        assertError(
                () -> service.retryKkiru(other, ai.getCommunityCommentId()),
                ErrorType.COMMUNITY_NOT_AUTHOR);
        assertError(
                () -> service.retryKkiru(other, 999_999L), ErrorType.COMMUNITY_COMMENT_NOT_FOUND);
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("FAILED가 아닌 댓글은 다시 물을 수 없다")
    void retryKkiruOnlyFailed() {
        for (KkiruStatus status :
                new KkiruStatus[] {
                    null,
                    KkiruStatus.PENDING,
                    KkiruStatus.ANSWERED,
                    KkiruStatus.NO_SOURCE,
                    KkiruStatus.OUT_OF_SCOPE
                }) {
            CommunityCommentEntity comment = commentWith(other, status);
            assertError(
                    () -> service.retryKkiru(other, comment.getCommunityCommentId()),
                    ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
        }
    }

    @Test
    @DisplayName("숨겨진·삭제된 댓글이거나 그 댓글이 달린 글이 숨겨졌으면 다시 물을 수 없다")
    void retryKkiruHiddenOrParentHidden() {
        CommunityCommentEntity hidden = commentWith(other, KkiruStatus.FAILED);
        hidden.hide();
        CommunityCommentEntity deleted = commentWith(other, KkiruStatus.FAILED);
        deleted.markDeleted();
        CommunityCommentEntity underHiddenPost = commentWith(other, KkiruStatus.FAILED);
        postRepository.findById(postId).orElseThrow().hide();
        entityManager.flush();

        assertError(
                () -> service.retryKkiru(other, hidden.getCommunityCommentId()),
                ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
        assertError(
                () -> service.retryKkiru(other, deleted.getCommunityCommentId()),
                ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
        assertError(
                () -> service.retryKkiru(other, underHiddenPost.getCommunityCommentId()),
                ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
    }
}
