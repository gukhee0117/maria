package com.app.maria.domain.community.mapper;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;

class CommunityWatchlistMapperTest {
    static SqlSessionFactory factory;
    static PooledDataSource source;
    static final LocalDateTime FIRST = LocalDateTime.of(2026, 11, 2, 0, 0);

    @BeforeAll
    static void configure() throws Exception {
        try (var reader = Resources.getResourceAsReader("mybatis-community-test-config.xml")) {
            factory = new SqlSessionFactoryBuilder().build(reader);
        }
        source = (PooledDataSource) factory.getConfiguration().getEnvironment().getDataSource();
        source.setUrl("jdbc:h2:mem:community_watchlist_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var c = source.getConnection();
                var st = c.createStatement()) {
            st.execute("CREATE TABLE customer(customer_id BIGINT PRIMARY KEY)");
            st.execute("INSERT INTO customer VALUES(51),(52)");
            st.execute(
                    "CREATE TABLE community_watchlist(customer_id BIGINT NOT NULL REFERENCES customer(customer_id),ticker VARCHAR(20) NOT NULL,created_at TIMESTAMP NOT NULL,PRIMARY KEY(customer_id,ticker))");
        }
    }

    @BeforeEach
    void clear() throws Exception {
        try (var c = source.getConnection();
                var st = c.createStatement()) {
            st.execute("DELETE FROM community_watchlist");
        }
    }

    @AfterAll
    static void close() {
        source.forceCloseAll();
    }

    @Test
    void duplicatePreservesTimeAndCustomerIsolation() {
        try (var s = factory.openSession(true)) {
            var m = s.getMapper(CommunityWatchlistMapper.class);
            m.add(51L, "005930", FIRST);
            m.add(51L, "AAPL", FIRST.plusHours(1));
            m.add(51L, "005930", FIRST.plusDays(1));
            m.add(52L, "005930", FIRST);
            assertThat(m.findTickers(51L)).containsExactly("005930", "AAPL");
            m.remove(52L, "AAPL");
            m.remove(52L, "005930");
            m.remove(52L, "005930");
            assertThat(m.findTickers(52L)).isEmpty();
            assertThat(m.findTickers(51L)).containsExactly("005930", "AAPL");
        }
    }

    @Test
    void rollbackAndInjectionDoNotPersistChanges() {
        try (var s = factory.openSession(false)) {
            s.getMapper(CommunityWatchlistMapper.class).add(51L, "005930", FIRST);
            s.rollback();
        }
        try (var s = factory.openSession(true)) {
            var m = s.getMapper(CommunityWatchlistMapper.class);
            assertThat(m.findTickers(51L)).isEmpty();
            m.add(51L, "005930", FIRST);
            m.remove(51L, "' OR '1'='1");
            assertThat(m.findTickers(51L)).containsExactly("005930");
        }
    }

    @Test
    void concurrentDuplicatesKeepOneRow() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try (var s = factory.openSession(false)) {
                                        var mapper = s.getMapper(CommunityWatchlistMapper.class);
                                        assertThat(mapper.lockCustomer(51L)).isEqualTo(51L);
                                        mapper.add(51L, "005930", FIRST);
                                        s.commit();
                                    }
                                    return null;
                                }));
            start.countDown();
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
            try (var s = factory.openSession(true)) {
                assertThat(s.getMapper(CommunityWatchlistMapper.class).findTickers(51L))
                        .containsExactly("005930");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
