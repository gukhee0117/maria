package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.notification.dto.*;
import com.app.maria.domain.notification.mapper.*;
import com.app.maria.domain.notification.type.*;
import com.app.maria.global.clock.service.BusinessClockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;

@SpringJUnitConfig(NotificationReviewIntegrationTest.Beans.class)
class NotificationReviewIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomerNotificationService notifications;
    @Autowired CustomerNotificationSettingsService settings;
    @Autowired CustomerNotificationMapper notificationMapper;
    @Autowired CustomerNotificationSettingsMapper settingsMapper;
    @Autowired AccountMapper accounts;
    @Autowired BusinessClockService clock;
    @Autowired PlatformTransactionManager transactions;

    @Configuration
    @EnableTransactionManagement
    static class Beans {
        @Bean
        DataSource datasource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:pr383review;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                    "sa",
                    "");
        }

        @Bean
        JdbcTemplate jdbc(DataSource datasource) {
            return new JdbcTemplate(datasource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource datasource) {
            return new DataSourceTransactionManager(datasource);
        }

        @Bean
        SqlSessionFactory factory(DataSource datasource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(datasource);
            factory.setTypeAliasesPackage("com.app.maria.domain.notification.dto");
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            factory.setMapperLocations(
                    new ClassPathResource("mappers/notification/CustomerNotificationMapper.xml"),
                    new ClassPathResource(
                            "mappers/notification/CustomerNotificationSettingsMapper.xml"));
            return factory.getObject();
        }

        @Bean
        CustomerNotificationMapper notificationsMapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(CustomerNotificationMapper.class);
        }

        @Bean
        CustomerNotificationSettingsMapper settingsMapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory)
                    .getMapper(CustomerNotificationSettingsMapper.class);
        }

        @Bean
        AccountMapper accounts() {
            return mock(AccountMapper.class);
        }

        @Bean
        BusinessClockService clock() {
            return mock(BusinessClockService.class);
        }

        @Bean
        NotificationEmailVerificationService verification() {
            var verification = mock(NotificationEmailVerificationService.class);
            when(verification.isRegisteredEmailVerified(any(), any())).thenReturn(true);
            return verification;
        }

        @Bean
        CustomerNotificationSettingsService settings(
                CustomerNotificationSettingsMapper mapper,
                BusinessClockService clock,
                NotificationEmailVerificationService verification) {
            return new CustomerNotificationSettingsServiceImpl(mapper, clock, verification);
        }

        @Bean
        CustomerNotificationService notifications(
                CustomerNotificationMapper mapper,
                AccountMapper accounts,
                CustomerNotificationSettingsService settings,
                BusinessClockService clock,
                org.springframework.context.ApplicationEventPublisher events) {
            return new CustomerNotificationServiceImpl(mapper, accounts, clock, events);
        }
    }

    @BeforeEach
    void setup() {
        jdbc.execute("DROP TABLE IF EXISTS settlement_item");
        jdbc.execute("DROP TABLE IF EXISTS krw_exchange");
        jdbc.execute(
                "CREATE TABLE krw_exchange (exchange_id BIGINT PRIMARY KEY, account_id BIGINT, final_amount DECIMAL(20,2), settlement_status VARCHAR(20))");
        jdbc.execute(
                "CREATE TABLE settlement_item (item_id BIGINT PRIMARY KEY, batch_id BIGINT, exchange_id BIGINT, result VARCHAR(20), processed_at TIMESTAMP)");
        jdbc.execute("INSERT INTO krw_exchange VALUES (7,7,1234567,'FINALIZED')");
        jdbc.update("INSERT INTO settlement_item VALUES (1,10,7,'SUCCESS',?)", NOW);
        jdbc.execute("DROP TABLE IF EXISTS notification");
        jdbc.execute("DROP TABLE IF EXISTS customer_consent");
        jdbc.execute("DROP TABLE IF EXISTS customer");
        jdbc.execute(
                "CREATE TABLE customer (customer_id BIGINT PRIMARY KEY, email VARCHAR(100) DEFAULT 'test@example.com')");
        jdbc.execute("INSERT INTO customer(customer_id) VALUES (1),(2)");
        jdbc.execute(
                "CREATE TABLE customer_consent (customer_consent_id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL REFERENCES customer(customer_id), consent_type VARCHAR(30) NOT NULL, agreed BOOLEAN NOT NULL, changed_at TIMESTAMP NOT NULL)");
        jdbc.execute(
                "CREATE TABLE notification (notification_id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL REFERENCES customer(customer_id), notification_type VARCHAR(30) NOT NULL, title VARCHAR(100) NOT NULL, content VARCHAR(500) NOT NULL, related_type VARCHAR(30) NOT NULL, related_id BIGINT NOT NULL, read_at TIMESTAMP, created_at TIMESTAMP NOT NULL, UNIQUE(customer_id,notification_type,related_type,related_id))");
        reset(accounts, clock);
        when(accounts.selectByAccountId(7L))
                .thenReturn(Optional.of(AccountDTO.builder().accountId(7L).customerId(1L).build()));
        when(clock.now()).thenReturn(NOW);
    }

    private CustomerNotificationSettingsRequestDTO request(boolean value) {
        return new CustomerNotificationSettingsRequestDTO(
                value, value, value, value, value, value, value);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Test
    void settingWritesOnlyChangedFieldsWithOneBusinessTimestampAndRepeatIsNoop() {
        settings.update(
                1L,
                new CustomerNotificationSettingsRequestDTO(
                        false, false, false, true, true, true, true));
        assertThat(count("customer_consent")).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT DISTINCT changed_at FROM customer_consent",
                                LocalDateTime.class))
                .isEqualTo(NOW);
        settings.update(
                1L,
                new CustomerNotificationSettingsRequestDTO(
                        false, false, false, true, true, true, true));
        assertThat(count("customer_consent")).isEqualTo(2);
        assertThat(settings.get(2L).push()).isTrue();
    }

    @Test
    void latestConsentUsesBusinessTimeThenIdRatherThanLargestId() {
        jdbc.update(
                "INSERT INTO customer_consent VALUES (100,1,'NOTIFY_PUSH',FALSE,?),(1,1,'NOTIFY_PUSH',TRUE,?)",
                NOW.minusDays(1),
                NOW);
        assertThat(settings.get(1L).push()).isTrue();
        settingsMapper.insertConsent(1L, NotificationConsentType.NOTIFY_PUSH, false, NOW);
        assertThat(settings.get(1L).push()).isFalse();
    }

    @Test
    void normalInsertSurfacesLengthAndForeignKeyFailuresInsteadOfIgnoringThem() {
        assertThatThrownBy(
                        () ->
                                notificationMapper.insertEvent(
                                        1L,
                                        NotificationType.SETTLEMENT_FINALIZED,
                                        "SETTLEMENT_BATCH",
                                        10L,
                                        "x".repeat(101),
                                        "body",
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                notificationMapper.insertEvent(
                                        999L,
                                        NotificationType.SETTLEMENT_FINALIZED,
                                        "SETTLEMENT_BATCH",
                                        10L,
                                        "title",
                                        "body",
                                        NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("notification")).isZero();
    }

    @Test
    void completionFromAfterCommitIsPersistedAndRepeatedEventsAreSkipped() {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        s ->
                                TransactionSynchronizationManager.registerSynchronization(
                                        new TransactionSynchronization() {
                                            @Override
                                            public void afterCommit() {
                                                notifications.recordSettlementFinalized(7L, 10L);
                                            }
                                        }));
        assertThat(count("notification")).isEqualTo(1);
        assertThat(notifications.recordSettlementFinalized(7L, 10L)).isFalse();
        assertThat(count("notification")).isEqualTo(1);
        notifications.markAllRead(2L);
        assertThat(notifications.list(1L).get(0).getReadAt()).isNull();
        notifications.markAllRead(1L);
        assertThat(notifications.list(1L).get(0).getReadAt()).isEqualTo(NOW);
    }

    @Test
    void retiredReplySettingsCannotBeEnabledAndOldReplyHistoryIsHidden() {
        settings.update(
                1L,
                new CustomerNotificationSettingsRequestDTO(
                        true, false, false, true, true, true, true));
        assertThat(settings.get(1L).reply()).isFalse();
        assertThat(settings.get(1L).kiru()).isFalse();
        assertThat(count("customer_consent")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT consent_type FROM customer_consent", String.class))
                .isEqualTo("NOTIFY_EMAIL");
        notificationMapper.insertEvent(
                1L,
                NotificationType.COMMUNITY_COMMENT,
                "COMMUNITY_COMMENT",
                1L,
                "reply",
                "old reply",
                NOW);
        notificationMapper.insertEvent(
                1L, NotificationType.KIRU_REPLY, "COMMUNITY_COMMENT", 2L, "kiru", "old kiru", NOW);
        assertThat(count("notification")).isEqualTo(2);
        assertThat(notifications.list(1L)).isEmpty();
        notifications.recordSettlementFinalized(7L, 10L);
        assertThat(notifications.list(1L)).hasSize(1);
        assertThat(notifications.list(1L).get(0).getNotificationType())
                .isEqualTo(NotificationType.SETTLEMENT_FINALIZED);
    }

    @Test
    void enumAndVoMappingKeepsExistingJsonContract() throws Exception {
        notifications.recordSettlementFinalized(7L, 10L);
        assertThat(notificationMapper.selectRecentByCustomerId(1L, 50).get(0))
                .isInstanceOf(CustomerNotificationVO.class);
        assertThat(notifications.list(1L).get(0).getNotificationType())
                .isEqualTo(NotificationType.SETTLEMENT_FINALIZED);
        var mapper = new ObjectMapper().findAndRegisterModules();
        assertThat(
                        mapper.readTree(mapper.writeValueAsString(notifications.list(1L).get(0)))
                                .get("notificationType")
                                .asText())
                .isEqualTo("SETTLEMENT_FINALIZED");
    }

    @Test
    void concurrentSettingsRequestsNeverMixIndividualFields() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first =
                    pool.submit(
                            () -> {
                                await(start);
                                settings.update(1L, request(true));
                            });
            Future<?> second =
                    pool.submit(
                            () -> {
                                await(start);
                                settings.update(1L, request(false));
                            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            var result = settings.get(1L);
            assertThat(
                            List.of(
                                    result.push(),
                                    result.sms(),
                                    result.email(),
                                    result.settle(),
                                    result.band()))
                    .allMatch(value -> value.equals(result.push()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCompletionCreatesOnlyOneEvent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first =
                    pool.submit(
                            () -> {
                                await(start);
                                notifications.recordSettlementFinalized(7L, 10L);
                            });
            Future<?> second =
                    pool.submit(
                            () -> {
                                await(start);
                                notifications.recordSettlementFinalized(7L, 10L);
                            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(count("notification")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedSettingWriteRollsBackAllPartialRows() {
        jdbc.execute("ALTER TABLE customer_consent ADD CHECK (consent_type <> 'NOTIFY_EMAIL')");
        assertThatThrownBy(() -> settings.update(1L, request(true)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("customer_consent")).isZero();
    }

    @Test
    void visibleReadNeverMarksUnseenOrOtherCustomerNotifications() {
        jdbc.update(
                "INSERT INTO notification(customer_id,notification_type,title,content,related_type,related_id,created_at) VALUES (1,'SETTLEMENT_FINALIZED','one','body','SETTLEMENT_BATCH',1,?),(1,'SETTLEMENT_FINALIZED','two','body','SETTLEMENT_BATCH',2,?),(2,'SETTLEMENT_FINALIZED','other','body','SETTLEMENT_BATCH',3,?)",
                NOW,
                NOW,
                NOW);
        var ids =
                jdbc.queryForList(
                        "SELECT notification_id FROM notification ORDER BY notification_id",
                        Long.class);
        notifications.markVisibleRead(1L, List.of(ids.get(0), ids.get(2)));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification WHERE read_at IS NOT NULL",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification WHERE notification_id=?",
                                LocalDateTime.class,
                                ids.get(1)))
                .isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
