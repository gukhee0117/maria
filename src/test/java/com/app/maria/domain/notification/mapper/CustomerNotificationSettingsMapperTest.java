package com.app.maria.domain.notification.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.notification.dto.CustomerNotificationConsentDTO;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

class CustomerNotificationSettingsMapperTest {
    @Test
    void realQueryRejectsNullEmptyWhitespaceAndMissingCustomer() throws Exception {
        PooledDataSource source =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:notification_email;MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        try {
            try (Connection connection = source.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE customer (customer_id BIGINT PRIMARY KEY, email VARCHAR(100))");
                statement.execute(
                        "INSERT INTO customer VALUES (1, NULL), (2, ''), (3, '   '), (4, 'test@example.com')");
            }
            Configuration config =
                    new Configuration(
                            new Environment("test", new JdbcTransactionFactory(), source));
            config.getTypeAliasRegistry()
                    .registerAlias(
                            "CustomerNotificationConsentDTO", CustomerNotificationConsentDTO.class);
            String resource = "mappers/notification/CustomerNotificationSettingsMapper.xml";
            try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            }
            try (SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession()) {
                CustomerNotificationSettingsMapper mapper =
                        session.getMapper(CustomerNotificationSettingsMapper.class);
                assertThat(mapper.hasRegisteredEmail(1L)).isFalse();
                assertThat(mapper.hasRegisteredEmail(2L)).isFalse();
                assertThat(mapper.hasRegisteredEmail(3L)).isFalse();
                assertThat(mapper.hasRegisteredEmail(4L)).isTrue();
                assertThat(mapper.hasRegisteredEmail(999L)).isFalse();
            }
        } finally {
            source.forceCloseAll();
        }
    }
}
