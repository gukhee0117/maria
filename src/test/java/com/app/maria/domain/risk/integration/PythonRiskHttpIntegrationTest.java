package com.app.maria.domain.risk.integration;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.domain.risk.mapper.RiskSyncMapper;
import com.app.maria.domain.risk.mapper.StockRiskGradeMapper;
import com.app.maria.domain.risk.service.*;
import com.app.maria.global.client.risk.*;
import com.app.maria.global.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestClient;

/** Opt-in: real Uvicorn/FastAPI + production Java client/services + disposable MariaDB. */
class PythonRiskHttpIntegrationTest {
    static final String URL = "jdbc:mariadb://127.0.0.1:33380/risk_test";
    static final String TEST_KEY = "synthetic-http-integration-key";
    @TempDir Path temporary;
    Path riskRoot, python, fixture, directory;
    Process server;
    String baseUrl;
    DataSource dataSource;
    JdbcTemplate jdbc;
    SqlSessionFactory factory;
    StockRiskGradeMapper mapper;
    RiskSyncMapper syncMapper;
    RiskGradeStore store;
    AnnotationConfigApplicationContext context;
    PythonRiskClient client;
    KrxTradingCalendar calendar;
    RiskProperties properties;
    AtomicReference<Instant> instant;
    RiskMarketClock clock;

    @Configuration
    @EnableTransactionManagement
    static class Transactions {}

    @BeforeEach
    void setup() throws Exception {
        assumeTrue(System.getenv("RISK_HTTP_PROJECT") != null, "Real Python HTTP test is opt-in");
        assertThat(System.getenv("RISK_TEST_JDBC_URL")).isEqualTo(URL);
        riskRoot = Path.of(System.getenv("RISK_HTTP_PROJECT")).toRealPath();
        python = riskRoot.resolve(".venv/Scripts/python.exe");
        fixture = riskRoot.resolve("tests/integration/live_risk_server.py");
        directory = temporary.resolve("synthetic-python");
        configureDataSource(new DriverManagerDataSource(URL, "root", "risk-test-only"));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("risk_test");
        execute(new ClassPathResource("risk/legacy-schema.sql"));
        execute(new FileSystemResource("db/migrations/001_stock_risk_grade_version.sql"));
        int port;
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            port = socket.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        server =
                new ProcessBuilder(
                                python.toString(),
                                "-B",
                                fixture.toString(),
                                "serve",
                                "--directory",
                                directory.toString(),
                                "--port",
                                String.valueOf(port))
                        .directory(riskRoot.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(temporary.resolve("python-server.log").toFile())
                        .start();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean ready = false;
        while (System.nanoTime() < deadline) {
            try {
                var response =
                        http.send(
                                HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                                        .timeout(Duration.ofSeconds(1))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    ready = true;
                    break;
                }
            } catch (java.io.IOException e) {
                /* bounded startup polling */
            }
            if (!server.isAlive()) break;
            Thread.sleep(50);
        }
        assertThat(ready)
                .withFailMessage("Real Python server failed; inspect temporary python-server.log")
                .isTrue();
        var manifest = new ObjectMapper().readTree(directory.resolve("manifest.json").toFile());
        properties = new RiskProperties();
        properties.setServiceKey(TEST_KEY);
        properties.setCalendarPath(directory.resolve("calendar.csv").toString());
        properties.setExpectedCalendarVersion(manifest.get("calendarVersion").asText());
        calendar = new KrxTradingCalendar(properties);
        client = new PythonRiskClient(RestClient.builder().baseUrl(baseUrl).build(), properties);
        instant =
                new AtomicReference<>(ZonedDateTime.parse("2026-10-28T17:10:00+09:00").toInstant());
        clock =
                new RiskMarketClock(
                        new Clock() {
                            public ZoneId getZone() {
                                return RiskMarketClock.ZONE;
                            }

                            public Clock withZone(ZoneId z) {
                                return this;
                            }

                            public Instant instant() {
                                return instant.get();
                            }
                        });
    }

    void configureDataSource(DataSource source) throws Exception {
        if (context != null) context.close();
        dataSource = source;
        jdbc = new JdbcTemplate(source);
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        var bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(config);
        bean.setMapperLocations(
                new ClassPathResource("mappers/risk/StockRiskGradeMapper.xml"),
                new ClassPathResource("mappers/risk/RiskSyncMapper.xml"));
        factory = bean.getObject();
        var template = new SqlSessionTemplate(factory);
        mapper = template.getMapper(StockRiskGradeMapper.class);
        syncMapper = template.getMapper(RiskSyncMapper.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(
                "transactionManager",
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(source));
        context.registerBean(StockRiskGradeMapper.class, () -> mapper);
        context.register(Transactions.class, RiskGradeStore.class);
        context.refresh();
        store = context.getBean(RiskGradeStore.class);
    }

    void execute(Resource resource) throws Exception {
        try (var c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, resource);
        }
    }

    RiskGradeSyncService.Result pull() {
        return new RiskGradeSyncServiceImpl(client, store).pull(calendar.load(), clock.now());
    }

    RiskGradeService reader() {
        return new RiskGradeServiceImpl(mapper, calendar, clock);
    }

    long version() {
        return jdbc.queryForObject(
                "SELECT analysis_version FROM stock_risk_grade WHERE domestic_product_id=1",
                Long.class);
    }

    void setTime(String text) throws Exception {
        Files.writeString(directory.resolve("clock.txt"), text, StandardCharsets.UTF_8);
        instant.set(ZonedDateTime.parse(text).toInstant());
    }

    void publish(String ticker, int revision) throws Exception {
        var process =
                new ProcessBuilder(
                                python.toString(),
                                "-B",
                                fixture.toString(),
                                "publish",
                                "--directory",
                                directory.toString(),
                                "--ticker",
                                ticker,
                                "--revision",
                                String.valueOf(revision))
                        .directory(riskRoot.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(
                                temporary
                                        .resolve("publish-" + ticker + "-" + revision + ".log")
                                        .toFile())
                        .start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            stop(process);
            fail("Fixture publication timed out");
        }
        assertThat(process.exitValue()).isZero();
    }

    static void stop(Process process) throws Exception {
        // Windows venv launcher can have a different PID from Uvicorn.
        var children = process.descendants().toList();
        children.forEach(p -> p.destroyForcibly());
        process.destroyForcibly();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        for (var child : children) {
            // Parent exit alone does not release log handles owned by its children.
            child.onExit().get(5, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (dataSource != null)
                new JdbcTemplate(new DriverManagerDataSource(URL, "root", "risk-test-only"))
                        .execute("SET GLOBAL time_zone='+09:00'");
        } finally {
            if (context != null) context.close();
            if (server != null) stop(server);
        }
    }

    @Test
    void actualPythonResponseMatchesDtoDuplicateCorrectionAndPartialPublication() throws Exception {
        var first = client.fetch();
        assertThat(first).hasSize(1);
        assertThat(first.get(0).analysisVersion()).isEqualTo(1);
        assertThat(first.get(0).priceTo()).isEqualTo(LocalDate.parse("2026-10-28"));
        assertThat(first.get(0).asOf().toInstant())
                .isEqualTo(Instant.parse("2026-10-28T06:41:01.123456Z"));
        var wire =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(baseUrl + "/grades"))
                                        .header("X-Risk-Service-Key", TEST_KEY)
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(new ObjectMapper().readTree(wire.body()).get(0).get("asOf").asText())
                .endsWith("+09:00");
        assertThat(first.get(0).asOf().getNano()).isEqualTo(123456000);
        assertThat(calendar.load().latest(clock.now())).isEqualTo(first.get(0).priceTo());
        assertThat(pull().changed()).isEqualTo(1);
        assertThat(version()).isEqualTo(1);
        var before = jdbc.queryForMap("SELECT * FROM stock_risk_grade");
        assertThat(pull().duplicates()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM stock_risk_grade")).isEqualTo(before);
        publish("005930", 2);
        assertThat(client.fetch().get(0).analysisVersion()).isEqualTo(2);
        assertThat(pull().changed()).isEqualTo(1);
        assertThat(version()).isEqualTo(2);
        LocalDateTime stored =
                jdbc.queryForObject(
                        "SELECT calculated_at FROM stock_risk_grade",
                        (r, n) -> r.getObject(1, LocalDateTime.class));
        assertThat(stored).isEqualTo(LocalDateTime.parse("2026-10-28T15:41:02"));
        publish("000660", 1);
        var partial = pull();
        assertThat(partial.received()).isEqualTo(2);
        assertThat(partial.changed()).isEqualTo(1);
        assertThat(partial.duplicates()).isEqualTo(1);
        assertThat(reader().findUsable()).hasSize(2);
    }

    @Test
    void authenticationFailurePreservesStoredGradeAndDoesNotScheduleFailureRetry()
            throws Exception {
        var http = HttpClient.newHttpClient();
        var missing =
                http.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/grades")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(pull().changed()).isEqualTo(1);
        var old = jdbc.queryForMap("SELECT * FROM stock_risk_grade");
        properties.setServiceKey("wrong-synthetic-key");
        assertThatThrownBy(client::fetch)
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(
                                                com.app.maria.global.exception.ErrorType
                                                        .RISK_INVALID_RESPONSE));
        var coordinator =
                new RiskSyncCoordinator(
                        properties,
                        clock,
                        calendar,
                        new MariaDbRiskSyncLease(factory),
                        syncMapper,
                        new RiskGradeSyncServiceImpl(client, store));
        coordinator.tick();
        assertThat(syncMapper.findState().getNextRetryAt()).isNull();
        assertThat(syncMapper.findState().getLastErrorCode()).isEqualTo("RISK_INVALID_RESPONSE");
        assertThat(jdbc.queryForMap("SELECT * FROM stock_risk_grade")).isEqualTo(old);
        properties.setServiceKey(TEST_KEY);
        assertThat(pull().duplicates()).isEqualTo(1);
    }

    @Test
    void realHttpFailureRetryAndExpiredExclusionCannotResurrectStoredGrade() throws Exception {
        var sync = new RiskGradeSyncServiceImpl(client, store);
        var coordinator =
                new RiskSyncCoordinator(
                        properties,
                        clock,
                        calendar,
                        new MariaDbRiskSyncLease(factory),
                        syncMapper,
                        sync);
        coordinator.tick();
        assertThat(version()).isEqualTo(1);
        var old = jdbc.queryForMap("SELECT * FROM stock_risk_grade");
        Path database = directory.resolve("risk.sqlite3"),
                offline = directory.resolve("risk.offline.sqlite3");
        Files.move(database, offline);
        setTime("2026-10-28T17:15:00+09:00");
        coordinator.tick();
        assertThat(syncMapper.findState().getNextRetryAt()).isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM stock_risk_grade")).isEqualTo(old);
        setTime("2026-11-02T17:10:00+09:00");
        assertThat(reader().findUsable()).hasSize(1); // three sessions
        setTime("2026-11-03T17:10:00+09:00");
        assertThat(reader().findUsable()).isEmpty(); // four sessions
        coordinator.tick();
        assertThat(syncMapper.findState().getLastErrorCode())
                .isEqualTo("RISK_UPSTREAM_UNAVAILABLE");
        Files.move(offline, database);
        setTime("2026-11-03T17:11:00+09:00");
        assertThat(client.fetch()).isEmpty(); // actual FastAPI excludes EXPIRED
        coordinator.tick();
        assertThat(syncMapper.findState().getLastRunKind()).isEqualTo("RETRY");
        assertThat(syncMapper.findState().getLastErrorCode()).isNull();
        assertThat(reader().findUsable()).isEmpty();
        assertThat(jdbc.queryForMap("SELECT * FROM stock_risk_grade")).isEqualTo(old);
    }

    @Test
    void utcGlobalRequiresKstRiskSessionAndDatetimeStillRoundTrips() throws Exception {
        assertThat(pull().changed()).isEqualTo(1);
        jdbc.execute("SET GLOBAL time_zone='+00:00'");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class))
                .isEqualTo("+00:00");
        publish("005930", 2);
        assertThat(pull().rejected()).isEqualTo(1);
        assertThat(version()).isEqualTo(1);
        assertThatThrownBy(() -> reader().findUsable()).isInstanceOf(AppException.class);
        // Test-only risk DataSource initialization; do not change the business pool.
        var isolatedKst =
                new DriverManagerDataSource(URL, "root", "risk-test-only") {
                    @Override
                    public Connection getConnection() throws SQLException {
                        var c = super.getConnection();
                        try (var s = c.createStatement()) {
                            s.execute("SET time_zone='+09:00'");
                        }
                        return c;
                    }
                };
        configureDataSource(isolatedKst);
        assertThat(jdbc.queryForObject("SELECT @@global.time_zone", String.class))
                .isEqualTo("+00:00");
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class))
                .isEqualTo("+09:00");
        assertThat(pull().changed()).isEqualTo(1);
        assertThat(version()).isEqualTo(2);
        var incoming = client.fetch().get(0);
        LocalDateTime stored =
                jdbc.queryForObject(
                        "SELECT calculated_at FROM stock_risk_grade",
                        (r, n) -> r.getObject(1, LocalDateTime.class));
        assertThat(stored.atZone(RiskMarketClock.ZONE).toInstant())
                .isEqualTo(
                        incoming.asOf()
                                .toInstant()
                                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(pull().duplicates()).isEqualTo(1);
    }
}
