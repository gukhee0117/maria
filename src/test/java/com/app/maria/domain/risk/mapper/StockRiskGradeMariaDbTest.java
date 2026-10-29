package com.app.maria.domain.risk.mapper;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

import com.app.maria.domain.risk.RiskTestSupport;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.service.*;
import com.app.maria.global.client.risk.*;
import com.app.maria.global.exception.AppException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.*;
import org.springframework.core.io.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

@ExtendWith(OutputCaptureExtension.class)
class StockRiskGradeMariaDbTest {
    DataSource dataSource;
    JdbcTemplate jdbc;
    SqlSessionFactory factory;
    StockRiskGradeMapper mapper, realMapper;
    RiskSyncMapper syncMapper;
    RiskGradeStore store;
    AnnotationConfigApplicationContext context;
    PlatformTransactionManager manager;

    @Configuration
    @EnableTransactionManagement
    static class Transactions {}

    static class IsolatedTestDataSource extends DriverManagerDataSource {
        IsolatedTestDataSource(String url) {
            super(url, "root", "risk-test-only");
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            try (var statement = connection.createStatement()) {
                statement.execute("SET innodb_lock_wait_timeout=1");
            }
            return connection;
        }
    }

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("RISK_TEST_JDBC_URL");
        assumeTrue(url != null, "Disposable MariaDB test is opt-in");
        // Hard allowlist: never accept a production/remote URL or credentials.
        assertThat(url).isEqualTo("jdbc:mariadb://127.0.0.1:33379/risk_test");
        dataSource = new IsolatedTestDataSource(url);
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("risk_test");
        script(new ClassPathResource("risk/legacy-schema.sql"));
        migrate();
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        var bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(config);
        bean.setMapperLocations(
                new ClassPathResource("mappers/risk/StockRiskGradeMapper.xml"),
                new ClassPathResource("mappers/risk/RiskSyncMapper.xml"));
        factory = bean.getObject();
        var template = new SqlSessionTemplate(factory);
        realMapper = template.getMapper(StockRiskGradeMapper.class);
        mapper =
                mock(
                        StockRiskGradeMapper.class,
                        org.mockito.AdditionalAnswers.delegatesTo(realMapper));
        doAnswer(
                        i -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isTrue();
                            return realMapper.lockProductsByTicker(i.getArgument(0));
                        })
                .when(mapper)
                .lockProductsByTicker(anyString());
        syncMapper = template.getMapper(RiskSyncMapper.class);
        manager = new DataSourceTransactionManager(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
        context.registerBean(StockRiskGradeMapper.class, () -> mapper);
        context.register(Transactions.class, RiskGradeStore.class);
        context.refresh();
        store = context.getBean(RiskGradeStore.class);
        assertThat(AopUtils.isAopProxy(store)).isTrue();
    }

    @AfterEach
    void cleanup() {
        if (context != null) context.close();
    }

    void script(Resource resource) throws Exception {
        try (var c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, resource);
        }
    }

    void migrate() throws Exception {
        script(new FileSystemResource("db/migrations/001_stock_risk_grade_version.sql"));
    }

    ZonedDateTime received() {
        return RiskTestSupport.at("2026-10-28T17:10:00+09:00");
    }

    PythonGradeResponseDTO grade(long version) {
        return RiskTestSupport.grade("005930", "2026-10-28", version);
    }

    long count() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_risk_grade", Long.class);
    }

    long version() {
        return jdbc.queryForObject(
                "SELECT analysis_version FROM stock_risk_grade WHERE domestic_product_id=1",
                Long.class);
    }

    @Test
    void migrationPreservesExistingValuesAndZeroIsUnusableUntilVerified() throws Exception {
        script(new ClassPathResource("risk/legacy-schema.sql"));
        jdbc.update(
                "INSERT INTO stock_risk_grade VALUES (1,.1125,4,.2000,'2023-10-30','2026-10-28','2026-10-28 17:00:00','2026-10-28 17:01:00')");
        var before = jdbc.queryForMap("SELECT * FROM stock_risk_grade");
        migrate();
        var after = jdbc.queryForMap("SELECT * FROM stock_risk_grade");
        after.remove("analysis_version");
        assertThat(after).isEqualTo(before);
        assertThat(version()).isZero();
        var reader =
                new RiskGradeServiceImpl(
                        mapper,
                        RiskTestSupport.calendar(),
                        RiskTestSupport.clock("2026-10-28T17:10:00+09:00"));
        assertThat(reader.findUsable()).isEmpty();
        assertThat(store.store(grade(1), received())).isEqualTo(RiskGradeStore.Outcome.UPDATED);
        assertThat(reader.findUsable()).hasSize(1);
        assertThat(version()).isEqualTo(1);
        migrate(); // Rerun/recovery must not reset versions or erase rows.
        assertThat(version()).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void primaryKeyAndForeignKeyArePresent() {
        assertThat(
                        jdbc.queryForObject(
                                """
            SELECT count(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA='risk_test' AND TABLE_NAME='stock_risk_grade' AND CONSTRAINT_TYPE='PRIMARY KEY'
            """,
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
            SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA='risk_test' AND TABLE_NAME='stock_risk_grade' AND CONSTRAINT_NAME='PRIMARY'
            """,
                                String.class))
                .isEqualTo("domestic_product_id");
        store.store(grade(1), received());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO stock_risk_grade SELECT * FROM stock_risk_grade WHERE domestic_product_id=1"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void twoRealTransactionsInsertMissingGradeOnlyOnce() throws Exception {
        runConcurrent(
                () -> store.store(grade(1), received()), () -> store.store(grade(2), received()));
        assertThat(count()).isEqualTo(1);
        assertThat(version()).isEqualTo(2);
    }

    @Test
    void concurrentUpdatesAndDateFirstComparisonNeverRegress() throws Exception {
        store.store(grade(1), received());
        runConcurrent(
                () -> store.store(grade(2), received()), () -> store.store(grade(3), received()));
        assertThat(version()).isEqualTo(3);
        assertThat(store.store(RiskTestSupport.grade("005930", "2026-10-26", 999), received()))
                .isEqualTo(RiskGradeStore.Outcome.IGNORED);
        assertThat(version()).isEqualTo(3);
        // Date comes first even when the newer date's version is smaller.
        assertThat(store.store(RiskTestSupport.grade("005930", "2026-10-29", 1), received()))
                .isEqualTo(RiskGradeStore.Outcome.UPDATED);
        assertThat(version()).isEqualTo(1);
    }

    @Test
    void duplicateIsIdempotentAndDifferentContentIsLogged(CapturedOutput output) {
        store.store(grade(1), received());
        var before = jdbc.queryForMap("SELECT * FROM stock_risk_grade");
        assertThat(store.store(grade(1), received().plusMinutes(5)))
                .isEqualTo(RiskGradeStore.Outcome.DUPLICATE);
        assertThat(jdbc.queryForMap("SELECT * FROM stock_risk_grade")).isEqualTo(before);
        var original = grade(1);
        var changed =
                new PythonGradeResponseDTO(
                        original.ticker(),
                        new BigDecimal(".4000"),
                        original.riskGrade(),
                        original.volatility(),
                        original.priceFrom(),
                        original.priceTo(),
                        original.asOf(),
                        1L);
        assertThat(store.store(changed, received())).isEqualTo(RiskGradeStore.Outcome.CONFLICT);
        assertThat(output).contains("RISK_VERSION_CONFLICT").contains("ticker=005930");
        assertThat(jdbc.queryForMap("SELECT * FROM stock_risk_grade")).isEqualTo(before);
        assertThat(store.store(grade(2), received())).isEqualTo(RiskGradeStore.Outcome.UPDATED);
        assertThat(version()).isEqualTo(2);
    }

    @Test
    void kstSecondsAndJdbcDatetimeRoundTripMatch() {
        var g = grade(1);
        var utc =
                new PythonGradeResponseDTO(
                        g.ticker(),
                        g.var975(),
                        g.riskGrade(),
                        g.volatility(),
                        g.priceFrom(),
                        g.priceTo(),
                        OffsetDateTime.parse("2026-10-28T06:40:20.123456Z"),
                        g.analysisVersion());
        store.store(utc, received());
        LocalDateTime value =
                jdbc.queryForObject(
                        "SELECT calculated_at FROM stock_risk_grade",
                        (rs, n) -> rs.getObject(1, LocalDateTime.class));
        assertThat(value).isEqualTo(LocalDateTime.parse("2026-10-28T15:40:20"));
        assertThat(value.atZone(RiskMarketClock.ZONE).toInstant())
                .isEqualTo(
                        utc.asOf().toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(store.store(utc, received())).isEqualTo(RiskGradeStore.Outcome.DUPLICATE);
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class))
                .isEqualTo("+09:00");
        assertThat(mapper.sessionUtcOffsetSeconds()).isEqualTo(32400);
        assertThat(
                        jdbc.queryForObject(
                                """
            SELECT DATETIME_PRECISION FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA='risk_test' AND TABLE_NAME='stock_risk_grade' AND COLUMN_NAME='calculated_at'
            """,
                                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("11.8.");
    }

    @Test
    void utcSessionFailsClosedWithoutChangingBusinessClockOrStoredData() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .execute(
                                                status -> {
                                                    jdbc.execute("SET time_zone='+00:00'");
                                                    return store.store(grade(1), received());
                                                }))
                .isInstanceOf(AppException.class);
        assertThat(count()).isZero();
        assertThat(mapper.sessionUtcOffsetSeconds()).isEqualTo(32400);
    }

    @Test
    void insertFailureRollsBackAndReleasesLockForRetry() {
        doAnswer(
                        i -> {
                            realMapper.insert(i.getArgument(0));
                            throw new DataIntegrityViolationException("test failure after INSERT");
                        })
                .when(mapper)
                .insert(any());
        assertThatThrownBy(() -> store.store(grade(1), received()))
                .isInstanceOf(DataAccessException.class);
        assertThat(count()).isZero();
        doAnswer(i -> realMapper.insert(i.getArgument(0))).when(mapper).insert(any());
        assertThat(store.store(grade(1), received())).isEqualTo(RiskGradeStore.Outcome.INSERTED);
    }

    @Test
    void parentForUpdateBlocksUntilTimeoutAndCoordinatorRetriesAfterRollback() throws Exception {
        var client = mock(PythonRiskClient.class);
        when(client.fetch()).thenReturn(List.of(grade(1)));
        var service = new RiskGradeSyncServiceImpl(client, store);
        var p = RiskTestSupport.properties();
        var time = new java.util.concurrent.atomic.AtomicReference<>(received().toInstant());
        Clock adjustable =
                new Clock() {
                    public ZoneId getZone() {
                        return RiskMarketClock.ZONE;
                    }

                    public Clock withZone(ZoneId zone) {
                        return this;
                    }

                    public Instant instant() {
                        return time.get();
                    }
                };
        var coordinator =
                new RiskSyncCoordinator(
                        p,
                        new RiskMarketClock(adjustable),
                        RiskTestSupport.calendar(),
                        new MariaDbRiskSyncLease(factory),
                        syncMapper,
                        service);
        try (var blocking = dataSource.getConnection()) {
            blocking.setAutoCommit(false);
            try (var s = blocking.createStatement()) {
                s.executeQuery(
                                "SELECT domestic_product_id FROM domestic_product WHERE domestic_product_id=1 FOR UPDATE")
                        .close();
            }
            coordinator.tick();
            assertThat(count()).isZero();
            assertThat(syncMapper.findState().getNextRetryAt()).isNotNull();
            blocking.rollback();
        }
        time.set(time.get().plusSeconds(60));
        coordinator.tick();
        assertThat(count()).isEqualTo(1);
        assertThat(syncMapper.findState().getLastRunKind()).isEqualTo("RETRY");
        assertThat(syncMapper.findState().getNextRetryAt()).isNull();
    }

    @Test
    void realDeadlockRollsBackVictimAndItsWriteCanBeRetried() throws Exception {
        store.store(grade(1), received());
        store.store(RiskTestSupport.grade("000660", "2026-10-28", 1), received());
        var gate = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> deadlockAttempt(1, 2, gate));
            var second = executor.submit(() -> deadlockAttempt(2, 1, gate));
            var results =
                    Arrays.asList(
                            first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Objects::nonNull).count()).isEqualTo(1);
            var victim = results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(victim.getMostSpecificCause()).isInstanceOf(SQLException.class);
            assertThat(((SQLException) victim.getMostSpecificCause()).getErrorCode())
                    .isEqualTo(1213);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM stock_risk_grade WHERE volatility=.1111",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM stock_risk_grade WHERE volatility=.2410",
                                    Integer.class))
                    .isEqualTo(1);
            // A fresh transaction can store the victim again; its previous transaction is gone.
            store.store(grade(2), received());
            store.store(RiskTestSupport.grade("000660", "2026-10-28", 2), received());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM stock_risk_grade WHERE analysis_version=2",
                                    Integer.class))
                    .isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    DataAccessException deadlockAttempt(int first, int second, CyclicBarrier gate) {
        try {
            new TransactionTemplate(manager)
                    .execute(
                            status -> {
                                jdbc.update(
                                        "UPDATE stock_risk_grade SET volatility=.1111 WHERE domestic_product_id=?",
                                        first);
                                try {
                                    gate.await(5, TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                                jdbc.queryForList(
                                        "SELECT domestic_product_id FROM stock_risk_grade WHERE domestic_product_id=? FOR UPDATE",
                                        second);
                                return null;
                            });
            return null;
        } catch (DataAccessException e) {
            return e;
        }
    }

    @Test
    void namedLeaseAndPersistentStatePreventCrossInstanceDoublePull() throws Exception {
        var one = new MariaDbRiskSyncLease(factory);
        var two = new MariaDbRiskSyncLease(factory);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var task =
                    executor.submit(
                            () ->
                                    one.runIfAcquired(
                                            () -> {
                                                held.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new IllegalStateException();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(two.runIfAcquired(() -> fail("must not overlap"))).isFalse();
            release.countDown();
            assertThat(task.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThatThrownBy(
                        () ->
                                one.runIfAcquired(
                                        () -> {
                                            throw new IllegalStateException("test interruption");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(two.runIfAcquired(() -> {})).isTrue();
        var calls = new AtomicInteger();
        RiskGradeSyncService sync =
                (cal, now) -> {
                    calls.incrementAndGet();
                    return new RiskGradeSyncService.Result(0, 0, 0, 0, 0, false);
                };
        var clock = RiskTestSupport.clock("2026-10-28T17:10:00+09:00");
        var p = RiskTestSupport.properties();
        new RiskSyncCoordinator(p, clock, RiskTestSupport.calendar(), one, syncMapper, sync).tick();
        new RiskSyncCoordinator(p, clock, RiskTestSupport.calendar(), two, syncMapper, sync).tick();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void failedAlterIsAtomicButDdlDoesNotRollBackWithTransaction() throws Exception {
        script(new ClassPathResource("risk/legacy-schema.sql"));
        jdbc.update(
                "INSERT INTO stock_risk_grade VALUES (1,.1125,4,.2000,'2023-10-30','2026-10-28','2026-10-28 17:00:00','2026-10-28 17:01:00')");
        assertThatThrownBy(
                        () ->
                                jdbc.execute(
                                        """
            ALTER TABLE stock_risk_grade ADD COLUMN analysis_version BIGINT NOT NULL DEFAULT 0,
            ADD CONSTRAINT invalid_test_check CHECK (risk_grade < 0)
            """))
                .isInstanceOf(DataAccessException.class);
        assertThat(count()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
            SELECT count(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='risk_test'
            AND TABLE_NAME='stock_risk_grade' AND COLUMN_NAME='analysis_version'
            """,
                                Integer.class))
                .isZero();
        try (var c = dataSource.getConnection();
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute(
                    "ALTER TABLE stock_risk_grade ADD COLUMN analysis_version BIGINT NOT NULL DEFAULT 0");
            c.rollback();
        }
        assertThat(
                        jdbc.queryForObject(
                                """
            SELECT count(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='risk_test'
            AND TABLE_NAME='stock_risk_grade' AND COLUMN_NAME='analysis_version'
            """,
                                Integer.class))
                .isEqualTo(1);
        migrate();
        assertThat(count()).isEqualTo(1);
        assertThat(version()).isZero();
    }

    private void runConcurrent(Callable<?> first, Callable<?> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a =
                    executor.submit(
                            () -> {
                                gate.await();
                                return first.call();
                            });
            var b =
                    executor.submit(
                            () -> {
                                gate.await();
                                return second.call();
                            });
            gate.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
