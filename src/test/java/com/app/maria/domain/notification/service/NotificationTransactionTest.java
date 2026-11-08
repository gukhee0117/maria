package com.app.maria.domain.notification.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.community.CommunityTestSchema;
import com.app.maria.domain.notification.event.NotificationDeliveryListener;
import com.app.maria.domain.notification.mapper.CustomerNotificationMapper;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.show-sql=false",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
        })
@Import({
    JpaConfig.class,
    CustomerNotificationServiceImpl.class,
    NotificationDeliveryListener.class,
    NotificationTransactionTest.Mappers.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationTransactionTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired CustomerNotificationService service;
    @Autowired CustomerNotificationMapper notificationMapper;

    @Autowired
    @Qualifier("jpaTransactionManager")
    PlatformTransactionManager transactions;

    @MockitoBean AccountMapper accounts;
    @MockitoBean BusinessClockService clock;
    @MockitoBean CustomerNotificationStream stream;
    @MockitoBean NotificationEmailService email;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);

    @TestConfiguration
    static class Mappers {
        @Bean
        SqlSessionFactory notificationSqlSessionFactory(DataSource datasource) throws Exception {
            SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
            bean.setDataSource(datasource);
            bean.setTypeAliasesPackage("com.app.maria.domain.notification.dto");
            org.apache.ibatis.session.Configuration config =
                    new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            bean.setConfiguration(config);
            bean.setMapperLocations(
                    new ClassPathResource("mappers/notification/CustomerNotificationMapper.xml"));
            return bean.getObject();
        }

        @Bean
        CustomerNotificationMapper notificationMapper(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory).getMapper(CustomerNotificationMapper.class);
        }
    }

    @BeforeAll
    void schema() {
        jdbc.execute("SET MODE MySQL");
        CommunityTestSchema.create(jdbc);
        jdbc.execute(
                "CREATE TABLE krw_exchange (exchange_id BIGINT PRIMARY KEY, account_id BIGINT, final_amount DECIMAL(20,2), settlement_status VARCHAR(20))");
        jdbc.execute(
                "CREATE TABLE settlement_item (item_id BIGINT PRIMARY KEY, batch_id BIGINT, exchange_id BIGINT, result VARCHAR(20), processed_at TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE customer_consent (customer_consent_id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT, consent_type VARCHAR(30), agreed BOOLEAN, changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE notification (notification_id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL, notification_type VARCHAR(30) NOT NULL, title VARCHAR(100) NOT NULL, content VARCHAR(500) NOT NULL, related_type VARCHAR(30) NOT NULL, related_id BIGINT NOT NULL, read_at TIMESTAMP, created_at TIMESTAMP NOT NULL, UNIQUE(customer_id,notification_type,related_type,related_id))");
    }

    @BeforeEach
    void data() {
        jdbc.execute("DELETE FROM settlement_item");
        jdbc.execute("DELETE FROM krw_exchange");
        jdbc.execute("DELETE FROM notification");
        jdbc.execute("DELETE FROM customer_consent");
        jdbc.execute("DELETE FROM community_comment");
        jdbc.execute("DELETE FROM customer");
        jdbc.execute(
                "INSERT INTO customer(customer_id,name,birth_date,investor_type,ci_hash,email) VALUES (1,'customer','1990-01-01','NEUTRAL','test','test@example.com')");
        when(clock.now()).thenReturn(NOW);
    }

    @AfterAll
    void cleanup() {
        jdbc.execute("DROP TABLE settlement_item");
        jdbc.execute("DROP TABLE krw_exchange");
        jdbc.execute("DROP TABLE notification");
        jdbc.execute("DROP TABLE customer_consent");
        CommunityTestSchema.drop(jdbc);
    }

    @Test
    void settlementNotificationCommitsWhenTriggeredFromAfterCommitCallback() {
        jdbc.execute("INSERT INTO krw_exchange VALUES (1,3,1234567,'FINALIZED')");
        jdbc.update("INSERT INTO settlement_item VALUES (1,20,1,'SUCCESS',?)", NOW);
        when(accounts.selectByAccountId(3L))
                .thenReturn(
                        java.util.Optional.of(
                                com.app.maria.domain.account.dto.AccountDTO.builder()
                                        .customerId(1L)
                                        .build()));
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        s ->
                                org.springframework.transaction.support
                                        .TransactionSynchronizationManager.registerSynchronization(
                                        new org.springframework.transaction.support
                                                .TransactionSynchronization() {
                                            @Override
                                            public void afterCommit() {
                                                service.recordSettlementFinalized(3L, 20L);
                                            }
                                        }));
        assertThat(count("notification")).isEqualTo(1);
        verify(stream).changed(1L);
    }

    @Test
    void settlementSummaryExcludesOtherAccountsBatchesAndOldRetryItems() {
        jdbc.execute(
                "INSERT INTO krw_exchange VALUES (1,3,1000000,'FINALIZED'), (2,3,2000000,'FINALIZED'), (3,4,9999999,'FINALIZED'), (4,3,9999999,'PROVISIONAL')");
        jdbc.update(
                "INSERT INTO settlement_item VALUES (1,20,1,'FAILED',?), (2,20,1,'SUCCESS',?), (3,20,1,'SUCCESS',?), (4,20,2,'SUCCESS',?), (5,21,1,'SUCCESS',?), (6,20,3,'SUCCESS',?), (7,20,4,'SUCCESS',?)",
                NOW,
                NOW,
                NOW.plusMinutes(1),
                NOW.plusMinutes(2),
                NOW,
                NOW,
                NOW);
        var details = notificationMapper.selectSettlementEmailDetails(3L, 20L);
        assertThat(details.getSettledAmount()).isEqualByComparingTo("3000000");
        assertThat(details.getCompletedAt()).isEqualTo(NOW.plusMinutes(2));
        jdbc.update("INSERT INTO settlement_item VALUES (8,20,2,'FAILED',?)", NOW.plusMinutes(3));
        assertThat(notificationMapper.selectSettlementEmailDetails(3L, 20L).getSettledAmount())
                .isEqualByComparingTo("1000000");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
