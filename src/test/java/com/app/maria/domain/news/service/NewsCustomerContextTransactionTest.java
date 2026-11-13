package com.app.maria.domain.news.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.news.dto.NewsContract.Context;
import com.app.maria.domain.news.dto.NewsContract.PersonalizationStatus;
import com.app.maria.global.clock.service.BusinessClockService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 뉴스 고객 문맥은 MyBatis(계좌·보유)와 JPA(성향)를 한 트랜잭션에서 읽는다. 실제 트랜잭션 매니저 두 개와 H2로, 두 매니저가 같은 DB 연결을 잡으려다 실패하던
 * 문제(CannotCreateTransactionException)가 다시 생기지 않는지 확인한다. 목 객체 단위 테스트로는 잡히지 않는다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties =
                "spring.datasource.url=jdbc:h2:mem:news_context_tx_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("inboundtransactiontest")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class NewsCustomerContextTransactionTest {
    private static final long CUSTOMER_ID = 9001L;

    @Autowired private NewsCustomerContextService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private BusinessClockService businessClockService;

    @BeforeAll
    void createSchema() {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS customer (
                    customer_id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, birth_date DATE NOT NULL,
                    phone VARCHAR(20), email VARCHAR(100), investor_type VARCHAR(20) NOT NULL,
                    ci_hash VARCHAR(64) NOT NULL UNIQUE, nickname VARCHAR(20),
                    investor_type_public BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS investor_assessment (
                    investor_assessment_id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL,
                    total_score INT NOT NULL, investor_type VARCHAR(20) NOT NULL,
                    answers_json VARCHAR(2000) NOT NULL, assessed_at TIMESTAMP NOT NULL
                )
                """);
        // 보유·테마·주문 쿼리(MyBatis)가 실제로 도는 데 필요한 컬럼만 둔다
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS account (account_id BIGINT PRIMARY KEY, customer_id BIGINT NOT NULL)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS domestic_product (domestic_product_id BIGINT PRIMARY KEY, ticker VARCHAR(20) NOT NULL, name VARCHAR(100) NOT NULL, type VARCHAR(15) NOT NULL, sector_name VARCHAR(50), domestic_stock_ratio DECIMAL(5, 2), inception_date DATE)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS domestic_stock_balance (account_id BIGINT NOT NULL, domestic_product_id BIGINT NOT NULL, qty DECIMAL(15, 4) NOT NULL, status VARCHAR(20) NOT NULL, last_purchase_date DATE, avg_purchase_price DECIMAL(15, 2))");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS stock_theme (stock_theme_id BIGINT PRIMARY KEY, name VARCHAR(30) NOT NULL)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS domestic_product_theme (domestic_product_id BIGINT NOT NULL, stock_theme_id BIGINT NOT NULL)");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS domestic_order (domestic_order_id BIGINT PRIMARY KEY, account_id BIGINT, domestic_product_id BIGINT, side VARCHAR(4), qty DECIMAL(15, 4), status VARCHAR(10), processed_at TIMESTAMP)");
        jdbc.update(
                "INSERT INTO customer (customer_id, name, birth_date, investor_type, ci_hash) VALUES (?, '뉴스', DATE '1990-01-01', 'NEUTRAL', 'news-context-tx-ci')",
                CUSTOMER_ID);
        jdbc.update(
                "INSERT INTO investor_assessment (customer_id, total_score, investor_type, answers_json, assessed_at) VALUES (?, 50, 'NEUTRAL', '[]', TIMESTAMP '2026-11-01 09:00:00')",
                CUSTOMER_ID);
        jdbc.update("INSERT INTO account VALUES (1, ?)", CUSTOMER_ID);
        jdbc.execute(
                "INSERT INTO domestic_product VALUES (1, '005930', '삼성전자', 'STOCK', '반도체', NULL, NULL)");
        jdbc.execute(
                "INSERT INTO domestic_stock_balance VALUES (1, 1, 10, 'HOLDING', DATE '2026-11-02', 70000)");
        jdbc.execute("INSERT INTO stock_theme VALUES (1, '반도체')");
        jdbc.execute("INSERT INTO domestic_product_theme VALUES (1, 1)");
        jdbc.execute(
                "INSERT INTO domestic_order VALUES (77, 1, 1, 'BUY', 10, 'EXECUTED', TIMESTAMP '2026-11-02 09:00:00')");
    }

    @Test
    void 브리핑_경로에서_보유와_성향을_한_트랜잭션으로_읽는다() {
        Mockito.when(businessClockService.now()).thenReturn(LocalDateTime.of(2026, 11, 13, 9, 0));

        // 브리핑(GET /api/customer/news/briefing)이 쓰는 경로: 보유·테마 우선순위·최근 주문(MyBatis) + 성향(JPA)
        Context context = service.loadForSelection(CUSTOMER_ID);

        assertThat(context.personalizationStatus()).isEqualTo(PersonalizationStatus.ACTIVE);
        assertThat(context.tickers()).containsExactly("005930");
        assertThat(context.themePriorities()).containsKey("반도체");
        assertThat(context.orderRevision()).isEqualTo(77L);
    }
}
