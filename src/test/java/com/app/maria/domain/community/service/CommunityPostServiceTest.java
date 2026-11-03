package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.request.PostUpdateRequestDTO;
import com.app.maria.domain.community.dto.request.PostWriteRequestDTO;
import com.app.maria.domain.community.dto.response.PostPageResponseDTO;
import com.app.maria.domain.community.dto.response.PostResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.entity.CommunityPostReactionEntity;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostReactionRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.domain.community.type.CommunityStatus;
import com.app.maria.domain.community.type.KkiruStatus;
import com.app.maria.domain.community.type.ReactionType;
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
    CommunityPostServiceImpl.class,
    CommunityPostAssembler.class,
    CommunityTextGuard.class,
    KkiruCallPlanner.class,
    KkiruCallGate.class,
    KkiruQuestionClassifier.class
})
@RecordApplicationEvents
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityPostServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired private CommunityPostService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private CommunityPostReactionRepository reactionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationEvents events;
    @MockitoBean private CommunityTickerService tickerService;
    @MockitoBean private BusinessClockService clock;

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
        jdbc.execute("DELETE FROM community_post_reaction");
        jdbc.execute("DELETE FROM community_post");
        jdbc.execute("DELETE FROM customer");
        when(clock.now()).thenReturn(NOW);
        me = insertCustomer("반도체개미", "ACTIVE", false);
        other = insertCustomer("장기보유러", "NEUTRAL", false);
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

    private static PostWriteRequestDTO req(String ticker, String content) {
        return new PostWriteRequestDTO(ticker, content);
    }

    private static void assertError(Runnable action, ErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AppException.class, e -> assertThat(e.getErrorType()).isEqualTo(expected));
    }

    private long postCount() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM community_post", Long.class);
    }

    private CommunityPostEntity savedPost(long customerId, String ticker, String content) {
        return postRepository.saveAndFlush(
                CommunityPostEntity.write(customerId, ticker, content, null, NOW));
    }

    // ---------- 쓰기 ----------

    @Test
    @DisplayName("글을 쓰면 업무시계 시각으로 저장되고 응답에는 작성자 닉네임만 있다")
    void write() {
        PostResponseDTO created = service.write(me, req("005930", "  삼성전자 이야기  "));

        assertThat(created.content()).isEqualTo("삼성전자 이야기");
        assertThat(created.ticker()).isEqualTo("005930");
        assertThat(created.authorNickname()).isEqualTo("반도체개미");
        assertThat(created.mine()).isTrue();
        assertThat(created.status()).isEqualTo(CommunityStatus.VISIBLE);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.kkiruStatus()).isNull();
        assertThat(created.editable()).isTrue();
        assertThat(created.likeCount()).isZero();
        verify(tickerService).requireExists("005930");
    }

    @Test
    @DisplayName("종목 없이 쓰면 일반 글이고 종목 코드는 대문자로 맞춘다")
    void writeGeneralAndNormalizeTicker() {
        assertThat(service.write(me, req(null, "제도 질문")).ticker()).isNull();
        assertThat(service.write(me, req("  ", "공백 종목")).ticker()).isNull();
        assertThat(service.write(me, req(" nvda ", "엔비디아")).ticker()).isEqualTo("NVDA");
        verify(tickerService).requireExists("NVDA");
    }

    @Test
    @DisplayName("본문이 비었거나 500자를 넘으면 COMMUNITY_CONTENT_INVALID로 거부한다")
    void writeContentLimits() {
        assertError(() -> service.write(me, req(null, "   ")), ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(() -> service.write(me, req(null, null)), ErrorType.COMMUNITY_CONTENT_INVALID);
        assertError(
                () -> service.write(me, req(null, "가".repeat(501))),
                ErrorType.COMMUNITY_CONTENT_INVALID);
        assertThat(service.write(me, req(null, "가".repeat(500))).content()).hasSize(500);
    }

    @Test
    @DisplayName("금칙어가 든 글은 저장하지 않고, 번호는 가려서 저장한다")
    void writeGuardsText() {
        assertError(
                () -> service.write(me, req(null, "이 종목 산 놈들 다 개새끼")),
                ErrorType.COMMUNITY_TEXT_BANNED);
        assertThat(postCount()).isZero();

        PostResponseDTO masked = service.write(me, req(null, "문의는 010-1234-5678 로"));

        assertThat(masked.content()).isEqualTo("문의는 010-****-**** 로");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT content FROM community_post WHERE community_post_id = ?",
                                String.class,
                                masked.id()))
                .doesNotContain("1234");
    }

    @Test
    @DisplayName("종목 마스터에 없는 종목이나 이상한 종목 문자열은 거부한다")
    void writeTickerValidation() {
        doThrow(new AppException(ErrorType.COMMUNITY_TICKER_NOT_FOUND))
                .when(tickerService)
                .requireExists("999999");

        assertError(
                () -> service.write(me, req("999999", "본문")), ErrorType.COMMUNITY_TICKER_NOT_FOUND);
        assertError(
                () -> service.write(me, req("A'; DROP TABLE x;--", "본문")),
                ErrorType.COMMUNITY_TICKER_NOT_FOUND);
        verify(tickerService, never()).requireExists("A'; DROP TABLE X;--");
        assertThat(postCount()).isZero();
    }

    // ---------- 끼루 호출 ----------

    @Test
    @DisplayName("@끼루가 있으면 PENDING으로 저장하고 호출 이벤트를 낸다")
    void writeWithKkiruMention() {
        PostResponseDTO created = service.write(me, req("000660", "@끼루 증설 공시가 있나요?"));

        assertThat(created.kkiruStatus()).isEqualTo(KkiruStatus.PENDING);
        assertThat(created.editable()).isFalse();
        List<KkiruCallRequestedEvent> published =
                events.stream(KkiruCallRequestedEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).postId()).isEqualTo(created.id());
        assertThat(published.get(0).commentId()).isNull();
    }

    @Test
    @DisplayName("매수·전망 질문은 OUT_OF_SCOPE로 끝나고 이벤트를 내지 않는다")
    void writeOutOfScope() {
        PostResponseDTO created = service.write(me, req("000660", "@끼루 지금 사도 돼요?"));

        assertThat(created.kkiruStatus()).isEqualTo(KkiruStatus.OUT_OF_SCOPE);
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("끼루를 부르지 않은 글은 상태가 없고 이벤트도 없다")
    void writeWithoutKkiru() {
        assertThat(service.write(me, req(null, "그냥 의견")).kkiruStatus()).isNull();
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("하루 20번을 넘겨 끼루를 부르면 거부하고 그 글은 저장하지 않는다")
    void writeKkiruDailyLimit() {
        for (int i = 0; i < 20; i++) {
            service.write(me, req(null, "@끼루 제도 질문 " + i));
        }

        assertError(
                () -> service.write(me, req(null, "@끼루 스물한 번째")),
                ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED);
        assertThat(postCount()).isEqualTo(20);
        // 끼루를 부르지 않는 글은 한도와 무관하다
        service.write(me, req(null, "일반 글은 계속 쓸 수 있다"));
    }

    // ---------- 수정 ----------

    @Test
    @DisplayName("본인 글을 고치면 본문이 바뀌고 수정됨 표시가 켜진다")
    void edit() {
        PostResponseDTO created = service.write(me, req("000660", "처음 글"));
        when(clock.now()).thenReturn(NOW.plusMinutes(3));

        PostResponseDTO edited = service.edit(me, created.id(), new PostUpdateRequestDTO("고친 글"));

        assertThat(edited.content()).isEqualTo("고친 글");
        assertThat(edited.edited()).isTrue();
        assertThat(edited.createdAt()).isEqualTo(NOW);
        assertThat(service.write(me, req(null, "안 고친 글")).edited()).isFalse();
    }

    @Test
    @DisplayName("남의 글은 고칠 수 없고 본문은 그대로다")
    void editByOther() {
        PostResponseDTO created = service.write(me, req(null, "내 글"));

        assertError(
                () -> service.edit(other, created.id(), new PostUpdateRequestDTO("가로채기")),
                ErrorType.COMMUNITY_NOT_AUTHOR);
        entityManager.clear();
        assertThat(postRepository.findById(created.id()).orElseThrow().getContent())
                .isEqualTo("내 글");
    }

    @Test
    @DisplayName("끼루가 답했거나 답하는 중인 글은 고칠 수 없다")
    void editBlockedByKkiru() {
        PostResponseDTO pending = service.write(me, req(null, "@끼루 질문"));
        assertError(
                () -> service.edit(me, pending.id(), new PostUpdateRequestDTO("바꾸기")),
                ErrorType.COMMUNITY_POST_NOT_EDITABLE);

        CommunityPostEntity answered = postRepository.findById(pending.id()).orElseThrow();
        answered.moveKkiruStatus(KkiruStatus.ANSWERED);
        postRepository.flush();
        assertError(
                () -> service.edit(me, pending.id(), new PostUpdateRequestDTO("바꾸기")),
                ErrorType.COMMUNITY_POST_NOT_EDITABLE);
    }

    @Test
    @DisplayName("고칠 때도 금칙어·길이를 검사하고 실패하면 본문이 바뀌지 않는다")
    void editValidation() {
        PostResponseDTO created = service.write(me, req(null, "처음 글"));

        assertError(
                () -> service.edit(me, created.id(), new PostUpdateRequestDTO("이 종목 산 놈들 다 개새끼")),
                ErrorType.COMMUNITY_TEXT_BANNED);
        assertError(
                () -> service.edit(me, created.id(), new PostUpdateRequestDTO(" ")),
                ErrorType.COMMUNITY_CONTENT_INVALID);
        entityManager.clear();
        CommunityPostEntity stored = postRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getContent()).isEqualTo("처음 글");
        assertThat(stored.getEditedAt()).isNull();
    }

    @Test
    @DisplayName("고치다가 @끼루를 새로 넣어도 끼루는 호출되지 않는다")
    void editDoesNotCallKkiru() {
        PostResponseDTO created = service.write(me, req(null, "처음 글"));

        PostResponseDTO edited =
                service.edit(me, created.id(), new PostUpdateRequestDTO("@끼루 이제 질문"));

        assertThat(edited.kkiruStatus()).isNull();
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("없는 글·삭제된 글을 고치면 COMMUNITY_POST_NOT_FOUND다")
    void editMissing() {
        PostResponseDTO created = service.write(me, req(null, "글"));
        service.delete(me, created.id());

        assertError(
                () -> service.edit(me, created.id(), new PostUpdateRequestDTO("x")),
                ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertError(
                () -> service.edit(me, 999_999L, new PostUpdateRequestDTO("x")),
                ErrorType.COMMUNITY_POST_NOT_FOUND);
    }

    // ---------- 삭제·상세 ----------

    @Test
    @DisplayName("삭제는 소프트 삭제이고 남의 글은 지울 수 없다")
    void delete() {
        PostResponseDTO created = service.write(me, req(null, "글"));

        assertError(() -> service.delete(other, created.id()), ErrorType.COMMUNITY_NOT_AUTHOR);
        service.delete(me, created.id());

        entityManager.flush(); // 변경을 DB에 반영한 뒤에 비워야 저장된 값을 다시 읽는다
        entityManager.clear();
        assertThat(postRepository.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(CommunityStatus.DELETED);
        assertError(() -> service.delete(me, created.id()), ErrorType.COMMUNITY_POST_NOT_FOUND);
    }

    @Test
    @DisplayName("삭제된 글은 댓글이 없으면 찾을 수 없고, 댓글이 있으면 안내 문구로 보인다")
    void detailOfDeleted() {
        CommunityPostEntity alone = savedPost(me, null, "혼자 지운 글");
        alone.markDeleted();
        CommunityPostEntity withComment = savedPost(me, null, "댓글 달린 글");
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        withComment.getCommunityPostId(), null, other, "댓글", null, NOW));
        withComment.markDeleted();
        postRepository.flush();

        assertError(
                () -> service.detail(me, alone.getCommunityPostId()),
                ErrorType.COMMUNITY_POST_NOT_FOUND);
        PostResponseDTO masked = service.detail(me, withComment.getCommunityPostId());
        assertThat(masked.content()).isEqualTo(CommunityPostAssembler.DELETED_TEXT);
        assertThat(masked.status()).isEqualTo(CommunityStatus.DELETED);
        assertThat(masked.content()).doesNotContain("댓글 달린 글");
    }

    @Test
    @DisplayName("신고로 숨겨진 글은 작성자가 지울 수 없다")
    void cannotDeleteHiddenPost() {
        CommunityPostEntity post = savedPost(me, null, "신고당한 글");
        post.hide();
        postRepository.flush();

        assertError(
                () -> service.delete(me, post.getCommunityPostId()),
                ErrorType.COMMUNITY_POST_NOT_EDITABLE);
        assertThat(post.getStatus()).isEqualTo(CommunityStatus.HIDDEN);
    }

    @Test
    @DisplayName("숨김 글은 본문 대신 안내 문구가 나가고 수정할 수 없다")
    void detailOfHidden() {
        CommunityPostEntity post = savedPost(me, null, "신고당한 비밀 본문");
        post.hide();
        postRepository.flush();

        PostResponseDTO hidden = service.detail(other, post.getCommunityPostId());

        assertThat(hidden.content()).isEqualTo(CommunityPostAssembler.HIDDEN_TEXT);
        assertThat(hidden.content()).doesNotContain("비밀");
        assertThat(service.detail(me, post.getCommunityPostId()).editable()).isFalse();
        assertError(() -> service.detail(me, 999_999L), ErrorType.COMMUNITY_POST_NOT_FOUND);
    }

    // ---------- 목록 ----------

    @Test
    @DisplayName("목록은 최신순 커서로 겹침·누락 없이 이어지고 마지막 쪽의 nextCursor는 null이다")
    void listPaging() {
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            expected.add(0, savedPost(me, "000660", "글 " + i).getCommunityPostId());
        }

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        do {
            PostPageResponseDTO page = service.list(me, null, null, cursor, 2);
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
        savedPost(me, null, "1");
        savedPost(me, null, "2");

        PostPageResponseDTO page = service.list(me, null, null, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("목록·인기글의 종목은 앞뒤 공백을 빼고 대문자로 맞춘다(글쓰기와 같다), 인기글도 일반 글을 고를 수 있다")
    void tickerIsStrippedAndGeneralPopular() {
        CommunityPostEntity hynix = savedPost(me, "000660", "하이닉스");
        CommunityPostEntity general = savedPost(me, null, "일반");
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        hynix.getCommunityPostId(), null, other, "c", null, NOW));
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        general.getCommunityPostId(), null, other, "c", null, NOW));

        assertThat(service.list(me, " 000660 ", null, null, 10).items())
                .extracting(PostResponseDTO::id)
                .containsExactly(hynix.getCommunityPostId());
        assertThat(service.popular(me, " 000660 ", 10).items())
                .extracting(PostResponseDTO::id)
                .containsExactly(hynix.getCommunityPostId());
        assertThat(service.popular(me, CommunityPostService.GENERAL, 10).items())
                .extracting(PostResponseDTO::id)
                .containsExactly(general.getCommunityPostId());
    }

    @Test
    @DisplayName("전체·종목·일반 글 필터")
    void listFilters() {
        CommunityPostEntity hynix = savedPost(me, "000660", "하이닉스");
        CommunityPostEntity samsung = savedPost(me, "005930", "삼성");
        CommunityPostEntity general = savedPost(me, null, "일반");

        assertThat(service.list(me, null, null, null, 10).items()).hasSize(3);
        assertThat(service.list(me, "000660", null, null, 10).items())
                .extracting(PostResponseDTO::id)
                .containsExactly(hynix.getCommunityPostId());
        assertThat(service.list(me, CommunityPostService.GENERAL, null, null, 10).items())
                .extracting(PostResponseDTO::id)
                .containsExactly(general.getCommunityPostId());
        assertThat(service.list(me, "general", null, null, 10).items())
                .extracting(PostResponseDTO::id)
                .containsExactly(general.getCommunityPostId());
        assertThat(samsung.getCommunityPostId()).isNotNull();
        assertError(
                () -> service.list(me, "A'; DROP", null, null, 10),
                ErrorType.COMMUNITY_TICKER_NOT_FOUND);
    }

    @Test
    @DisplayName("검색은 숨김 글 본문을 찾지 않고 %·_·따옴표를 글자 그대로 다룬다")
    void listSearch() {
        CommunityPostEntity visible = savedPost(me, "000660", "HBM 증설 100% 확정이래요");
        CommunityPostEntity hidden = savedPost(me, "000660", "비밀키워드가 든 글");
        hidden.hide();
        savedPost(me, "000660", "아무 상관 없는 글");
        postRepository.flush();

        assertThat(service.list(me, null, "hbm", null, 10).items())
                .extracting(PostResponseDTO::id)
                .containsExactly(visible.getCommunityPostId());
        assertThat(service.list(me, null, "100%", null, 10).items()).hasSize(1);
        assertThat(service.list(me, null, "%", null, 10).items()).hasSize(1);
        assertThat(service.list(me, null, "비밀키워드", null, 10).items()).isEmpty();
        assertThat(service.list(me, null, "' OR 1=1 --", null, 10).items()).isEmpty();
        assertError(
                () -> service.list(me, null, "가".repeat(31), null, 10),
                ErrorType.COMMUNITY_CONTENT_INVALID);
    }

    @Test
    @DisplayName("목록 항목에는 좋아요·댓글 수, 내가 눌렀는지, 내 글인지가 정확히 들어간다")
    void listAssemblesCounts() {
        CommunityPostEntity post = savedPost(me, "000660", "집계 글");
        reactionRepository.saveAndFlush(
                CommunityPostReactionEntity.of(
                        post.getCommunityPostId(), other, ReactionType.LIKE, NOW));
        reactionRepository.saveAndFlush(
                CommunityPostReactionEntity.of(
                        post.getCommunityPostId(), me, ReactionType.LIKE, NOW));
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(), null, other, "댓글 1", null, NOW));
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(), null, other, "댓글 2", null, NOW));
        CommunityCommentEntity hiddenComment =
                commentRepository.saveAndFlush(
                        CommunityCommentEntity.ofCustomer(
                                post.getCommunityPostId(), null, other, "숨김", null, NOW));
        hiddenComment.hide();
        commentRepository.flush();

        PostResponseDTO asMe = service.list(me, null, null, null, 10).items().get(0);
        PostResponseDTO asOther = service.list(other, null, null, null, 10).items().get(0);

        assertThat(asMe.likeCount()).isEqualTo(2);
        assertThat(asMe.commentCount()).isEqualTo(2); // 숨김 댓글은 세지 않는다
        assertThat(asMe.likedByMe()).isTrue();
        assertThat(asMe.mine()).isTrue();
        assertThat(asOther.likedByMe()).isTrue();
        assertThat(asOther.mine()).isFalse();
        assertThat(asOther.editable()).isFalse();
    }

    @Test
    @DisplayName("작성자 성향은 공개에 동의한 사람만 나가고, 미진단은 나가지 않는다")
    void investorTypeIsPublicOnlyWithConsent() {
        long publicUser = insertCustomer("성향공개", "AGGRESSIVE", true);
        long privateUser = insertCustomer("성향비공개", "AGGRESSIVE", false);
        long unassigned = insertCustomer("미진단공개", "UNASSIGNED", true);
        savedPost(publicUser, null, "공개 글");
        savedPost(privateUser, null, "비공개 글");
        savedPost(unassigned, null, "미진단 글");

        List<PostResponseDTO> items = service.list(me, null, null, null, 10).items();

        assertThat(byNickname(items, "성향공개").authorInvestorType()).isEqualTo("AGGRESSIVE");
        assertThat(byNickname(items, "성향비공개").authorInvestorType()).isNull();
        assertThat(byNickname(items, "미진단공개").authorInvestorType()).isNull();
    }

    private static PostResponseDTO byNickname(List<PostResponseDTO> items, String nickname) {
        return items.stream()
                .filter(i -> i.authorNickname().equals(nickname))
                .findFirst()
                .orElseThrow();
    }

    // ---------- 인기글 ----------

    @Test
    @DisplayName("인기글은 최근 7일 글 중 댓글이 많은 순이고 업무시계 기준이다")
    void popular() {
        CommunityPostEntity many = savedPost(me, "000660", "댓글 많은 글");
        CommunityPostEntity few = savedPost(me, "000660", "댓글 적은 글");
        CommunityPostEntity old =
                postRepository.saveAndFlush(
                        CommunityPostEntity.write(me, "000660", "오래된 글", null, NOW.minusDays(8)));
        for (int i = 0; i < 3; i++) {
            commentRepository.saveAndFlush(
                    CommunityCommentEntity.ofCustomer(
                            many.getCommunityPostId(), null, other, "c", null, NOW));
        }
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        few.getCommunityPostId(), null, other, "c", null, NOW));
        for (int i = 0; i < 9; i++) {
            commentRepository.saveAndFlush(
                    CommunityCommentEntity.ofCustomer(
                            old.getCommunityPostId(), null, other, "c", null, NOW));
        }

        List<PostResponseDTO> popular = service.popular(me, "000660", 10).items();

        assertThat(popular)
                .extracting(PostResponseDTO::id)
                .containsExactly(many.getCommunityPostId(), few.getCommunityPostId());
        assertThat(service.popular(me, null, 1).items()).hasSize(1);
        assertThat(service.popular(me, null, 10).nextCursor()).isNull();
        // 시계를 8일 앞으로 보내면 7일 창이 옮겨 가서 아무것도 남지 않는다
        when(clock.now()).thenReturn(NOW.plusDays(8));
        assertThat(service.popular(me, "000660", 10).items()).isEmpty();
    }

    @Test
    @DisplayName("업무시계 시각이 아닌 글 시각을 쓰는지: 시계를 옮기면 새 글의 시각도 옮겨 간다")
    void createdAtFollowsBusinessClock() {
        when(clock.now()).thenReturn(NOW.plusDays(30));

        assertThat(service.write(me, req(null, "한 달 뒤 글")).createdAt()).isEqualTo(NOW.plusDays(30));
    }

    // ---------- 끼루 다시 묻기 ----------

    private CommunityPostEntity failedPost(long customerId) {
        return postRepository.saveAndFlush(
                CommunityPostEntity.write(
                        customerId, "000660", "@끼루 공시 있나요?", KkiruStatus.FAILED, NOW));
    }

    @Test
    @DisplayName("다시 묻기: 내 FAILED 글이 PENDING이 되고 호출 이벤트가 나가며 새 글은 생기지 않는다")
    void retryKkiru() {
        CommunityPostEntity post = failedPost(me);
        long before = postCount();

        PostResponseDTO retried = service.retryKkiru(me, post.getCommunityPostId());

        assertThat(retried.kkiruStatus()).isEqualTo(KkiruStatus.PENDING);
        assertThat(
                        postRepository
                                .findById(post.getCommunityPostId())
                                .orElseThrow()
                                .getKkiruRetryCount())
                .isEqualTo(1);
        assertThat(postCount()).isEqualTo(before);
        List<KkiruCallRequestedEvent> published =
                events.stream(KkiruCallRequestedEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).postId()).isEqualTo(post.getCommunityPostId());
        assertThat(published.get(0).commentId()).isNull();
    }

    @Test
    @DisplayName("다시 묻기는 작성자 본인만 할 수 있고, 없는 글은 404다")
    void retryKkiruAuthorAndNotFound() {
        CommunityPostEntity post = failedPost(me);

        assertError(
                () -> service.retryKkiru(other, post.getCommunityPostId()),
                ErrorType.COMMUNITY_NOT_AUTHOR);
        assertError(() -> service.retryKkiru(me, 999_999L), ErrorType.COMMUNITY_POST_NOT_FOUND);
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
        assertThat(
                        postRepository
                                .findById(post.getCommunityPostId())
                                .orElseThrow()
                                .getKkiruStatus())
                .isEqualTo(KkiruStatus.FAILED);
    }

    @Test
    @DisplayName("FAILED가 아닌 글(호출 없음·진행 중·답함·근거 없음·범위 밖)은 다시 물을 수 없다")
    void retryKkiruOnlyFailed() {
        for (KkiruStatus status :
                new KkiruStatus[] {
                    null,
                    KkiruStatus.PENDING,
                    KkiruStatus.ANSWERED,
                    KkiruStatus.NO_SOURCE,
                    KkiruStatus.OUT_OF_SCOPE
                }) {
            CommunityPostEntity post =
                    postRepository.saveAndFlush(
                            CommunityPostEntity.write(me, "000660", "@끼루 질문", status, NOW));
            assertError(
                    () -> service.retryKkiru(me, post.getCommunityPostId()),
                    ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
        }
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("숨겨지거나 삭제된 글은 다시 물을 수 없다")
    void retryKkiruHiddenOrDeleted() {
        CommunityPostEntity hidden = failedPost(me);
        hidden.hide();
        CommunityPostEntity deleted = failedPost(me);
        deleted.markDeleted();
        entityManager.flush();

        assertError(
                () -> service.retryKkiru(me, hidden.getCommunityPostId()),
                ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
        assertError(
                () -> service.retryKkiru(me, deleted.getCommunityPostId()),
                ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
    }

    @Test
    @DisplayName("FAILED 글을 고쳐 멘션을 지우거나 범위 밖 질문으로 바꾸면 다시 물을 수 없다")
    void retryKkiruRechecksEditedContent() {
        CommunityPostEntity noMention = failedPost(me);
        service.edit(me, noMention.getCommunityPostId(), new PostUpdateRequestDTO("그냥 한마디"));
        CommunityPostEntity outOfScope = failedPost(me);
        service.edit(me, outOfScope.getCommunityPostId(), new PostUpdateRequestDTO("@끼루 사도 돼요?"));

        assertError(
                () -> service.retryKkiru(me, noMention.getCommunityPostId()),
                ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
        assertError(
                () -> service.retryKkiru(me, outOfScope.getCommunityPostId()),
                ErrorType.COMMUNITY_KKIRU_RETRY_NOT_ALLOWED);
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isZero();
    }

    @Test
    @DisplayName("다시 묻기도 하루 호출 예산을 쓴다: 오늘 만든 행 19개면 한 번은 되고 그다음은 막힌다")
    void retryKkiruCountsTowardDailyLimit() {
        CommunityPostEntity first = failedPost(me);
        CommunityPostEntity second = failedPost(me);
        for (int i = 0; i < 17; i++) {
            postRepository.save(
                    CommunityPostEntity.write(me, "000660", "@끼루 질문", KkiruStatus.ANSWERED, NOW));
        }
        entityManager.flush();

        assertThat(service.retryKkiru(me, first.getCommunityPostId()).kkiruStatus())
                .isEqualTo(KkiruStatus.PENDING);
        assertError(
                () -> service.retryKkiru(me, second.getCommunityPostId()),
                ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("며칠 전에 만든 FAILED 글을 많이 다시 물어도 오늘 20번을 넘지 못한다(리뷰 재현 시나리오)")
    void retryKkiruOldRowsCannotExceedDailyLimit() {
        List<CommunityPostEntity> oldFailed = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            oldFailed.add(
                    postRepository.save(
                            CommunityPostEntity.write(
                                    me,
                                    "000660",
                                    "@끼루 공시 있나요?",
                                    KkiruStatus.FAILED,
                                    NOW.minusDays(2))));
        }
        entityManager.flush();

        for (int i = 0; i < 20; i++) {
            assertThat(service.retryKkiru(me, oldFailed.get(i).getCommunityPostId()).kkiruStatus())
                    .isEqualTo(KkiruStatus.PENDING);
        }
        assertError(
                () -> service.retryKkiru(me, oldFailed.get(20).getCommunityPostId()),
                ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED);
        assertThat(events.stream(KkiruCallRequestedEvent.class).count()).isEqualTo(20);
    }
}
