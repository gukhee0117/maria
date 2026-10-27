package com.app.maria.global.clock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.global.audit.mapper.AuditLogMapper;
import com.app.maria.global.audit.service.AuditLogServiceImpl;
import com.app.maria.global.clock.dto.SystemClockDTO;
import com.app.maria.global.clock.dto.request.SystemClockChangeRequestDTO;
import com.app.maria.global.clock.mapper.SystemClockMapper;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SystemClockIntegrationTest {

    private static final LocalDateTime INITIAL_DATETIME = LocalDateTime.of(2026, 8, 5, 10, 0);

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private SystemClockMapper systemClockMapper;
    private BusinessClockService businessClockService;
    private SystemClockManagementService systemClockManagementService;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        dataSource =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:system_clock_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");

        Environment environment =
                new Environment("system-clock-test", new JdbcTransactionFactory(), dataSource);

        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SystemClockMapper.class);
        configuration.addMapper(AuditLogMapper.class);

        loadMapperXml(configuration, "mappers/clock/SystemClockMapper.xml");
        loadMapperXml(configuration, "mappers/audit/AuditLogMapper.xml");

        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void setUp() throws SQLException {
        resetSchema();

        sqlSession = sqlSessionFactory.openSession(true);
        systemClockMapper = sqlSession.getMapper(SystemClockMapper.class);
        AuditLogMapper auditLogMapper = sqlSession.getMapper(AuditLogMapper.class);

        businessClockService = new BusinessClockServiceImpl(systemClockMapper);
        systemClockManagementService =
                new SystemClockManagementServiceImpl(
                        systemClockMapper, new AuditLogServiceImpl(auditLogMapper));
    }

    @Test
    @DisplayName("SYSTEM_CLOCK 기준시각과 흐르는 현재 업무시각을 함께 조회한다")
    void selectsBaseAndCurrentDatetimeSeparately() {
        SystemClockDTO systemClock = systemClockMapper.selectSystemClock().orElseThrow();

        assertThat(systemClock.getBaseDatetime()).isEqualTo(INITIAL_DATETIME);
        assertThat(systemClock.getCurrentDatetime())
                .isBetween(INITIAL_DATETIME, INITIAL_DATETIME.plusSeconds(2));
        assertThat(systemClock.getReferenceRealDatetime()).isNotNull();
    }

    @AfterEach
    void closeSession() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    @Test
    @DisplayName("관리자가 지정한 시간으로 MARIA 업무시각을 변경하고 감사로그를 저장한다")
    void changesMariaBusinessDatetimeAndStoresAuditLog() throws SQLException {
        Long adminId = 7L;
        LocalDateTime requestedDatetime = LocalDateTime.of(2027, 8, 5, 9, 30);
        String reasonCode = "DEMO_TIME_CHANGE";

        LocalDateTime datetimeBeforeChange = businessClockService.now();
        assertThat(datetimeBeforeChange)
                .isBetween(INITIAL_DATETIME, INITIAL_DATETIME.plusSeconds(2));

        LocalDateTime actualTimeBeforeChange = LocalDateTime.now();

        LocalDateTime changedDatetime =
                systemClockManagementService.changeSystemTime(
                        adminId, new SystemClockChangeRequestDTO(requestedDatetime, reasonCode));

        LocalDateTime actualTimeAfterChange = LocalDateTime.now();

        assertThat(changedDatetime).isEqualTo(requestedDatetime);

        assertThat(businessClockService.now())
                .isBetween(requestedDatetime, requestedDatetime.plusSeconds(2));

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                """
                     SELECT admin_id,
                            target_table,
                            target_pk,
                            before_value,
                            after_value,
                            reason_code,
                            processed_at
                     FROM audit_log
                     """)) {

            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getLong("admin_id")).isEqualTo(adminId);
            assertThat(resultSet.getString("target_table")).isEqualTo("SYSTEM_CLOCK");
            assertThat(resultSet.getString("target_pk")).isEqualTo("1");
            LocalDateTime beforeValue = LocalDateTime.parse(resultSet.getString("before_value"));
            assertThat(beforeValue).isBetween(INITIAL_DATETIME, INITIAL_DATETIME.plusSeconds(2));
            assertThat(resultSet.getString("after_value")).isEqualTo(requestedDatetime.toString());
            assertThat(resultSet.getString("reason_code")).isEqualTo(reasonCode);
            assertThat(resultSet.getObject("processed_at", LocalDateTime.class))
                    .isBetween(
                            actualTimeBeforeChange.minusSeconds(1),
                            actualTimeAfterChange.plusSeconds(1));
            assertThat(resultSet.next()).isFalse();
        }
    }

    @Test
    @DisplayName("MARIA 업무시각은 실제 시간의 흐름만큼 증가한다")
    void mariaBusinessDatetimeMovesForward() throws InterruptedException {
        LocalDateTime before = businessClockService.now();
        sqlSession.commit();

        Thread.sleep(1_100);

        LocalDateTime after = businessClockService.now();

        assertThat(after).isAfter(before);
        assertThat(after).isBetween(before.plusSeconds(1), before.plusSeconds(2));
    }

    private static void loadMapperXml(Configuration configuration, String resource)
            throws IOException {
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            XMLMapperBuilder mapperBuilder =
                    new XMLMapperBuilder(
                            inputStream, configuration, resource, configuration.getSqlFragments());
            mapperBuilder.parse();
        }
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
                    CREATE TABLE system_clock (
                        clock_id BIGINT PRIMARY KEY,
                        current_datetime DATETIME NOT NULL,
                        reference_real_datetime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT chk_system_clock_singleton CHECK (clock_id = 1)
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE audit_log (
                        audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
                        admin_id BIGINT NOT NULL,
                        target_table VARCHAR(50) NOT NULL,
                        target_pk VARCHAR(50) NOT NULL,
                        before_value TEXT,
                        after_value TEXT,
                        reason_code VARCHAR(30),
                        processed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute(
                    """
                    INSERT INTO system_clock (clock_id, current_datetime)
                    VALUES (1, TIMESTAMP '2026-08-05 10:00:00')
                    """);
        }
    }
}
