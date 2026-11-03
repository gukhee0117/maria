package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.response.TickerRankResponseDTO;
import com.app.maria.domain.community.dto.response.TickerResponseDTO;
import com.app.maria.domain.community.entity.CommunityCommentEntity;
import com.app.maria.domain.community.entity.CommunityPostEntity;
import com.app.maria.domain.community.kkiru.TrendReasonService;
import com.app.maria.domain.community.mapper.CommunityTickerMapper;
import com.app.maria.domain.community.repository.CommunityCommentRepository;
import com.app.maria.domain.community.repository.CommunityPostRepository;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import jakarta.persistence.EntityManager;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/** 화제 순위: 글+댓글 점수, 제외 대상, 날짜 경계, 정렬, 이유 연결. */
@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({JpaConfig.class, TransactionConfig.class, CommunityTickerServiceImpl.class})
@Transactional(transactionManager = "jpaTransactionManager")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommunityTickerServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 15, 0);
    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final LocalDateTime YESTERDAY = LocalDateTime.of(2026, 11, 2, 23, 59, 59);

    @Autowired private CommunityTickerService service;
    @Autowired private CommunityPostRepository postRepository;
    @Autowired private CommunityCommentRepository commentRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private CommunityTickerMapper tickerMapper;
    @MockitoBean private TrendReasonService trendReasons;
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
        when(tickerMapper.selectTickersByCodes(any()))
                .thenReturn(
                        List.of(
                                new TickerResponseDTO("000660", "SK하이닉스", "DOMESTIC"),
                                new TickerResponseDTO("005930", "삼성전자", "DOMESTIC"),
                                new TickerResponseDTO("035420", "NAVER", "DOMESTIC")));
    }

    private CommunityPostEntity post(String ticker, LocalDateTime at) {
        return postRepository.saveAndFlush(CommunityPostEntity.write(1L, ticker, "글", null, at));
    }

    private CommunityCommentEntity comment(CommunityPostEntity post, LocalDateTime at) {
        return commentRepository.saveAndFlush(
                CommunityCommentEntity.ofCustomer(
                        post.getCommunityPostId(), null, 2L, "댓글", null, at));
    }

    @Test
    @DisplayName("점수는 오늘 글 수 + 오늘 댓글 수이고, 점수 내림차순·같으면 종목코드 순이다")
    void scoreIsPostsPlusComments() {
        CommunityPostEntity samsung = post("005930", TODAY);
        post("005930", TODAY);
        CommunityPostEntity hynix = post("000660", TODAY);
        comment(hynix, TODAY);
        comment(hynix, TODAY);
        comment(hynix, TODAY);
        post("035420", TODAY);
        comment(samsung, TODAY); // 삼성전자: 글 2 + 댓글 1 = 3, 하이닉스: 글 1 + 댓글 3 = 4, NAVER: 1

        List<TickerRankResponseDTO> ranking = service.ranking(12);

        assertThat(ranking)
                .extracting(TickerRankResponseDTO::ticker)
                .containsExactly("000660", "005930", "035420");
        assertThat(ranking.get(0).rank()).isEqualTo(1);
        assertThat(ranking.get(0).postCount()).isEqualTo(1);
        assertThat(ranking.get(0).commentCount()).isEqualTo(3);
        assertThat(ranking.get(0).name()).isEqualTo("SK하이닉스");
        assertThat(ranking.get(1).postCount()).isEqualTo(2);
        assertThat(ranking.get(1).commentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("점수가 같으면 종목코드 오름차순")
    void tieBreaksByTicker() {
        post("035420", TODAY);
        post("000660", TODAY);
        post("005930", TODAY);

        assertThat(service.ranking(12))
                .extracting(TickerRankResponseDTO::ticker)
                .containsExactly("000660", "005930", "035420");
    }

    @Test
    @DisplayName("어제 것·숨김·삭제·끼루 댓글·종목 없는 글의 댓글은 세지 않는다")
    void exclusions() {
        CommunityPostEntity visible = post("000660", TODAY);
        post("000660", YESTERDAY);
        CommunityPostEntity hidden = post("000660", TODAY);
        hidden.hide();
        CommunityPostEntity deleted = post("000660", TODAY);
        deleted.markDeleted();
        comment(visible, YESTERDAY);
        CommunityCommentEntity hiddenComment = comment(visible, TODAY);
        hiddenComment.hide();
        CommunityCommentEntity deletedComment = comment(visible, TODAY);
        deletedComment.markDeleted();
        commentRepository.saveAndFlush(
                CommunityCommentEntity.ofAi(
                        visible.getCommunityPostId(), null, "끼루 답", null, TODAY));
        comment(hidden, TODAY); // 숨겨진 글의 댓글
        CommunityPostEntity general = post(null, TODAY);
        comment(general, TODAY);
        entityManager.flush();

        List<TickerRankResponseDTO> ranking = service.ranking(12);

        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).ticker()).isEqualTo("000660");
        assertThat(ranking.get(0).postCount()).isEqualTo(1);
        assertThat(ranking.get(0).commentCount()).isZero();
    }

    @Test
    @DisplayName("글은 없고 댓글만 오늘 달린 종목도(오래된 글에 오늘 댓글) 순위에 오른다")
    void commentsOnOldPostCount() {
        CommunityPostEntity old = post("005930", YESTERDAY);
        comment(old, TODAY);

        List<TickerRankResponseDTO> ranking = service.ranking(12);

        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).postCount()).isZero();
        assertThat(ranking.get(0).commentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("size만큼만 돌려주고, 이유는 이유 서비스가 준 값을 싣는다(없으면 null)")
    void sizeAndReason() {
        post("005930", TODAY);
        post("005930", TODAY);
        post("000660", TODAY);
        post("035420", TODAY);
        when(trendReasons.reasonOrRequest("005930")).thenReturn("배당 이야기가 나와요.");
        when(trendReasons.reasonOrRequest("000660")).thenReturn(null);

        List<TickerRankResponseDTO> ranking = service.ranking(2);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).ticker()).isEqualTo("005930");
        assertThat(ranking.get(0).reason()).isEqualTo("배당 이야기가 나와요.");
        assertThat(ranking.get(1).reason()).isNull();
        verify(trendReasons, never()).reasonOrRequest("035420"); // 순위 밖 종목은 만들지 않는다
    }

    @Test
    @DisplayName("오늘 글이 없으면 빈 목록이고 이유 서비스도 부르지 않는다")
    void empty() {
        post("005930", YESTERDAY);

        assertThat(service.ranking(12)).isEmpty();
        verify(trendReasons, never()).reasonOrRequest(any());
    }
}
