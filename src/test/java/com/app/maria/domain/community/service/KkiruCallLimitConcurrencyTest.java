package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.community.dto.request.PostWriteRequestDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import com.app.maria.global.config.TransactionConfig;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 끼루 한도 확인과 호출 예약(PENDING 저장)의 원자성. 다른 트랜잭션이 잠금을 쥔 채 호출을 예약하는 동안 뒤 요청이 기다렸다가, 앞 호출이 커밋되면 그 호출까지 세어
 * 막히는지 본다. 서비스를 실제 별도 트랜잭션으로 실행해야 하므로 테스트 트랜잭션을 쓰지 않는다.
 *
 * <p>못 잡는 것: H2의 기본 격리수준은 READ COMMITTED라서 write()의 READ_COMMITTED 지정을 빼도 통과한다. 그 지정이 필요한지는
 * MariaDB(REPEATABLE READ 기본)에서만 드러난다.
 */
@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "maria.community.kkiru.max-calls-per-day=1",
            "maria.community.kkiru.daily-answer-limit=1"
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KkiruCallLimitConcurrencyTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 15, 0);

    @Autowired private CommunityPostService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @MockitoBean private CommunityTickerService tickerService;
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
        jdbc.execute("DELETE FROM community_post");
        jdbc.execute("DELETE FROM customer");
        when(clock.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("같은 고객의 호출은 앞 호출의 커밋을 기다렸다가 하루 한도로 막힌다")
    void customerLimitWaitsForInFlightCall() throws Exception {
        long me = insertCustomer("반도체개미");

        assertBlockedThenRejected(
                "SELECT customer_id FROM customer WHERE customer_id = " + me + " FOR UPDATE",
                me,
                me,
                ErrorType.COMMUNITY_KKIRU_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("다른 고객의 호출도 전체 상한 확인은 앞 호출의 커밋을 기다렸다가 막힌다")
    void dailyLimitWaitsForInFlightCall() throws Exception {
        long first = insertCustomer("반도체개미");
        long second = insertCustomer("장기보유러");

        assertBlockedThenRejected(
                "SELECT gate_id FROM kiru_call_gate WHERE gate_id = 1 FOR UPDATE",
                first,
                second,
                ErrorType.COMMUNITY_KKIRU_BUSY);
    }

    private void assertBlockedThenRejected(
            String lockSql, long firstCaller, long secondCaller, ErrorType expected)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection first = dataSource.getConnection()) {
            // 앞 호출이 진행 중인 상황: 다른 트랜잭션이 잠금을 쥐고 있다.
            first.setAutoCommit(false);
            try (PreparedStatement lock = first.prepareStatement(lockSql)) {
                lock.executeQuery();
            }

            Future<?> late =
                    executor.submit(
                            () ->
                                    service.write(
                                            secondCaller,
                                            new PostWriteRequestDTO(null, "@끼루 배당 알려줘")));

            // 잠금을 기다려야 한다. 기다리지 않으면 둘 다 한도 미만으로 보고 통과한다.
            assertThatThrownBy(() -> late.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            // 앞 호출이 PENDING을 저장하고 커밋한다.
            try (PreparedStatement insert =
                    first.prepareStatement(
                            "INSERT INTO community_post (customer_id, content, kiru_status, status,"
                                    + " created_at) VALUES (?, '@끼루 앞 호출', 'PENDING', 'VISIBLE', ?)")) {
                insert.setLong(1, firstCaller);
                insert.setTimestamp(2, Timestamp.valueOf(NOW));
                insert.executeUpdate();
            }
            first.commit();

            assertThatThrownBy(() -> late.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(AppException.class)
                    .satisfies(
                            e ->
                                    assertThat(((AppException) e.getCause()).getErrorType())
                                            .isEqualTo(expected));
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM community_post", Long.class))
                .isEqualTo(1);
    }

    private long insertCustomer(String nickname) {
        jdbc.update(
                "INSERT INTO customer (name, birth_date, investor_type, ci_hash, nickname,"
                        + " investor_type_public) VALUES ('홍길동', '1990-01-01', 'NEUTRAL', ?, ?,"
                        + " FALSE)",
                "hash-" + System.nanoTime(),
                nickname);
        return jdbc.queryForObject("SELECT MAX(customer_id) FROM customer", Long.class);
    }
}
