package com.app.maria.domain.stockpackage.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.domestic.type.DomesticOrderSide;
import com.app.maria.domain.domestic.type.DomesticOrderStatus;
import com.app.maria.domain.domestic.type.DomesticStockStatus;
import com.app.maria.domain.stockpackage.type.StockOrderRejectReason;
import com.app.maria.domain.stockpackage.vo.StockBalanceVO;
import com.app.maria.domain.stockpackage.vo.StockOrderVO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 원본 DDL의 주문·잔고·환전 테이블과 실제 XML을 H2 MySQL 모드에서 실행한다. 실 MariaDB/FK 전체 검증과는 구분한다. */
class StockOrderMapperTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 10, 0);
    private static final long ACCOUNT = 70L;
    private static final long PRODUCT = 1L;

    private Connection connection;
    private StockOrderMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        String url =
                "jdbc:h2:mem:stockorder_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        connection = DriverManager.getConnection(url, "sa", "");
        String ddl = Files.readString(Path.of("db/01_ria_ddl.sql"));
        for (String table :
                List.of(
                        "domestic_product",
                        "domestic_order",
                        "domestic_stock_balance",
                        "krw_exchange",
                        "stock_package")) {
            var matcher =
                    Pattern.compile("(?ms)^CREATE TABLE " + table + " \\(.*?^\\) ENGINE=.*?;")
                            .matcher(ddl);
            assertThat(matcher.find()).as("원본 DDL 테이블: %s", table).isTrue();
            sql(matcher.group().replaceFirst("(?s)\\s+ENGINE=.*$", ";"));
        }
        // account는 REGEXP 제약이 있어 H2에서 그대로 만들지 않고, 이 매퍼가 쓰는 컬럼만 둔다
        sql(
                "CREATE TABLE account (account_id BIGINT AUTO_INCREMENT PRIMARY KEY, customer_id BIGINT NOT NULL DEFAULT 7, amount DECIMAL(15,0) NOT NULL DEFAULT 0)");
        sql("INSERT INTO account (account_id, customer_id, amount) VALUES (70, 7, 10000000)");
        sql("CREATE TABLE customer (customer_id BIGINT PRIMARY KEY, ci_hash VARCHAR(64) NOT NULL)");
        sql("INSERT INTO customer (customer_id, ci_hash) VALUES (7, 'ci-7')");
        sql(
                "INSERT INTO domestic_product (domestic_product_id,ticker,name,type) VALUES (1,'005930','삼성전자','STOCK')");
        sql("INSERT INTO stock_package (stock_package_id,customer_id,name) VALUES (5,7,'내 패키지')");
        sql("INSERT INTO stock_package (stock_package_id,customer_id,name) VALUES (6,7,'두번째')");
        sql("INSERT INTO stock_package (stock_package_id,customer_id,name) VALUES (9,8,'남의 패키지')");

        var dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        var config =
                new Configuration(
                        new Environment(
                                "stockorder-test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mappers/stockpackage/StockOrderMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(StockOrderMapper.class);
    }

    private void sql(String text) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(text);
        }
    }

    private StockOrderVO executed(String key) {
        return StockOrderVO.builder()
                .accountId(ACCOUNT)
                .domesticProductId(PRODUCT)
                .side(DomesticOrderSide.BUY)
                .qty(new BigDecimal("10"))
                .price(new BigDecimal("100000"))
                .amount(new BigDecimal("-1000040"))
                .feeAmount(new BigDecimal("40"))
                .taxAmount(BigDecimal.ZERO)
                .channel("CUSTOMER")
                .idempotencyKey(key)
                .status(DomesticOrderStatus.EXECUTED)
                .processedAt(NOW)
                .build();
    }

    @Test
    @DisplayName("증권사에 못 보낸 고객 체결 주문만 골라 ciHash와 함께 주고, 표시를 남기면 빠진다")
    void unrecordedOrdersLeaveTheListOnceMarked() {
        StockOrderVO pending = executed("o1:1");
        mapper.insertOrder(pending);
        StockOrderVO generator = executed("o2:1");
        generator.setChannel("GENERATOR");
        mapper.insertOrder(generator);

        var list = mapper.selectUnrecordedCustomerOrders(10);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getDomesticOrderId()).isEqualTo(pending.getDomesticOrderId());
        assertThat(list.get(0).getCiHash()).isEqualTo("ci-7");
        assertThat(list.get(0).getTicker()).isEqualTo("005930");

        assertThat(mapper.markSecuritiesRecorded(pending.getDomesticOrderId())).isEqualTo(1);
        assertThat(mapper.markSecuritiesRecorded(pending.getDomesticOrderId())).isZero();
        assertThat(mapper.selectUnrecordedCustomerOrders(10)).isEmpty();
    }

    @Test
    @DisplayName("체결 주문을 넣으면 ID가 채워지고 주문 키로 이름·종목코드와 함께 다시 읽힌다")
    void insertAndFindByKey() {
        StockOrderVO order = executed("k1:1");
        mapper.insertOrder(order);
        assertThat(order.getDomesticOrderId()).isNotNull();

        StockOrderVO read = mapper.selectByIdempotencyKey(ACCOUNT, "k1:1").orElseThrow();
        assertThat(read.getName()).isEqualTo("삼성전자");
        assertThat(read.getTicker()).isEqualTo("005930");
        assertThat(read.getStatus()).isEqualTo(DomesticOrderStatus.EXECUTED);
        assertThat(read.getAmount()).isEqualByComparingTo("-1000040");
        assertThat(read.getFeeAmount()).isEqualByComparingTo("40");
        assertThat(mapper.selectByIdempotencyKey(ACCOUNT, "없는키")).isEmpty();
        assertThat(mapper.selectByIdempotencyKey(999L, "k1:1")).isEmpty();
    }

    @Test
    @DisplayName("거부 주문은 가격·금액 없이 사유와 함께 저장된다")
    void insertRejected() {
        StockOrderVO rejected =
                StockOrderVO.builder()
                        .accountId(ACCOUNT)
                        .domesticProductId(PRODUCT)
                        .side(DomesticOrderSide.BUY)
                        .qty(new BigDecimal("3"))
                        .feeAmount(BigDecimal.ZERO)
                        .taxAmount(BigDecimal.ZERO)
                        .channel("CUSTOMER")
                        .idempotencyKey("k2:1")
                        .status(DomesticOrderStatus.REJECTED)
                        .rejectReason(StockOrderRejectReason.INSUFFICIENT_CASH)
                        .processedAt(NOW)
                        .build();
        mapper.insertOrder(rejected);

        StockOrderVO read = mapper.selectByIdempotencyKey(ACCOUNT, "k2:1").orElseThrow();
        assertThat(read.getStatus()).isEqualTo(DomesticOrderStatus.REJECTED);
        assertThat(read.getRejectReason()).isEqualTo(StockOrderRejectReason.INSUFFICIENT_CASH);
        assertThat(read.getPrice()).isNull();
        assertThat(read.getAmount()).isNull();
    }

    @Test
    @DisplayName("같은 계좌에서 같은 주문 키를 두 번 넣으면 DB가 막는다")
    void duplicateKeyRejected() {
        mapper.insertOrder(executed("k3:1"));
        assertThatThrownBy(() -> mapper.insertOrder(executed("k3:1")))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("잔고를 새로 만들고 갱신하며, 잠금 조회로 읽는다")
    void balanceInsertUpdateSelect() {
        assertThat(mapper.selectBalanceForUpdate(ACCOUNT, PRODUCT)).isEmpty();

        StockBalanceVO balance =
                StockBalanceVO.builder()
                        .accountId(ACCOUNT)
                        .domesticProductId(PRODUCT)
                        .qty(new BigDecimal("10"))
                        .status(DomesticStockStatus.HOLDING)
                        .lastPurchaseDate(NOW)
                        .avgPurchasePrice(new BigDecimal("100000"))
                        .build();
        mapper.insertBalance(balance);
        assertThat(balance.getDomesticStockBalanceId()).isNotNull();

        StockBalanceVO read = mapper.selectBalanceForUpdate(ACCOUNT, PRODUCT).orElseThrow();
        read.setQty(new BigDecimal("6"));
        read.setStatus(DomesticStockStatus.PARTIALLY_SOLD);
        mapper.updateBalance(read);

        StockBalanceVO after = mapper.selectBalanceForUpdate(ACCOUNT, PRODUCT).orElseThrow();
        assertThat(after.getQty()).isEqualByComparingTo("6");
        assertThat(after.getStatus()).isEqualTo(DomesticStockStatus.PARTIALLY_SOLD);
        assertThat(after.getAvgPurchasePrice()).isEqualByComparingTo("100000");
    }

    private long amountOf(long accountId) throws Exception {
        try (var rs =
                connection
                        .createStatement()
                        .executeQuery(
                                "SELECT amount FROM account WHERE account_id = " + accountId)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("예수금은 기록된 체결 주문의 금액만큼만 바뀌고, 거부 주문이나 남의 계좌 주문은 바꾸지 않는다")
    void applyOrderAmount() throws Exception {
        StockOrderVO buy = executed("p:1"); // amount -1,000,040
        mapper.insertOrder(buy);
        mapper.applyOrderAmount(ACCOUNT, buy.getDomesticOrderId());
        assertThat(amountOf(ACCOUNT)).isEqualTo(8_999_960L);

        StockOrderVO sell = executed("p:2");
        sell.setSide(DomesticOrderSide.SELL);
        sell.setAmount(new BigDecimal("399184"));
        mapper.insertOrder(sell);
        mapper.applyOrderAmount(ACCOUNT, sell.getDomesticOrderId());
        assertThat(amountOf(ACCOUNT)).isEqualTo(9_399_144L);

        StockOrderVO rejected = inPackage(5, "p:3", DomesticOrderStatus.REJECTED, NOW);
        mapper.insertOrder(rejected);
        mapper.applyOrderAmount(ACCOUNT, rejected.getDomesticOrderId());
        mapper.applyOrderAmount(999L, buy.getDomesticOrderId()); // 다른 계좌 번호로는 반영되지 않는다
        assertThat(amountOf(ACCOUNT)).isEqualTo(9_399_144L);
    }

    @Test
    @DisplayName("가정산이 없으면 합은 0이다")
    void sumProvisionalEmpty() {
        assertThat(mapper.sumProvisionalAmount(ACCOUNT)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("가정산(PROVISIONAL) 금액만 더하고 확정산은 뺀다")
    void sumProvisional() throws Exception {
        // 같은 세션에서 같은 쿼리를 두 번 부르면 MyBatis 로컬 캐시가 먼저 값을 돌려주므로, 없을 때의 합(0)은 다른 테스트에서 본다
        sql(
                "INSERT INTO krw_exchange (account_id,order_id,provisional_amount,provisional_at,settlement_status) VALUES (70,1,1500000,'2026-11-17 10:00:00','PROVISIONAL')");
        sql(
                "INSERT INTO krw_exchange (account_id,order_id,provisional_amount,provisional_at,final_amount,settlement_status) VALUES (70,2,700000,'2026-11-10 10:00:00',700000,'FINALIZED')");
        sql(
                "INSERT INTO krw_exchange (account_id,order_id,provisional_amount,provisional_at,settlement_status) VALUES (71,3,900000,'2026-11-17 10:00:00','PROVISIONAL')");
        assertThat(mapper.sumProvisionalAmount(ACCOUNT)).isEqualByComparingTo("1500000");
    }

    private StockOrderVO inPackage(
            long packageId, String key, DomesticOrderStatus status, LocalDateTime at) {
        StockOrderVO.StockOrderVOBuilder b =
                StockOrderVO.builder()
                        .accountId(ACCOUNT)
                        .domesticProductId(PRODUCT)
                        .stockPackageId(packageId)
                        .side(DomesticOrderSide.BUY)
                        .qty(new BigDecimal("1"))
                        .feeAmount(BigDecimal.ZERO)
                        .taxAmount(BigDecimal.ZERO)
                        .channel("CUSTOMER")
                        .idempotencyKey(key)
                        .status(status)
                        .processedAt(at);
        if (status == DomesticOrderStatus.EXECUTED) {
            b.price(new BigDecimal("100000")).amount(new BigDecimal("-100004"));
        } else {
            b.rejectReason(StockOrderRejectReason.INSUFFICIENT_CASH);
        }
        return b.build();
    }

    @Test
    @DisplayName("고객 주문 내역은 내 패키지 것만 최신순으로, 패키지 이름과 함께 나온다")
    void ordersByCustomer() {
        mapper.insertOrder(inPackage(5, "a:1", DomesticOrderStatus.EXECUTED, NOW));
        mapper.insertOrder(inPackage(6, "b:1", DomesticOrderStatus.REJECTED, NOW.plusMinutes(5)));
        mapper.insertOrder(inPackage(9, "c:1", DomesticOrderStatus.EXECUTED, NOW.plusMinutes(9)));

        List<StockOrderVO> all = mapper.selectOrdersByCustomer(7L, null, 100);
        assertThat(all).extracting(StockOrderVO::getIdempotencyKey).containsExactly("b:1", "a:1");
        assertThat(all.get(0).getPackageName()).isEqualTo("두번째");
        assertThat(all.get(0).getRejectReason())
                .isEqualTo(StockOrderRejectReason.INSUFFICIENT_CASH);

        assertThat(mapper.selectOrdersByCustomer(7L, 5L, 100))
                .extracting(StockOrderVO::getIdempotencyKey)
                .containsExactly("a:1");
        assertThat(mapper.selectOrdersByCustomer(7L, 9L, 100)).isEmpty();
        assertThat(mapper.selectOrdersByCustomer(7L, null, 1)).hasSize(1);
    }

    @Test
    @DisplayName("패키지 보유 계산용 조회는 체결만 오래된 순으로 준다")
    void executedByPackageAsc() {
        mapper.insertOrder(inPackage(5, "x:2", DomesticOrderStatus.EXECUTED, NOW.plusMinutes(3)));
        mapper.insertOrder(inPackage(5, "x:1", DomesticOrderStatus.EXECUTED, NOW));
        mapper.insertOrder(inPackage(5, "x:3", DomesticOrderStatus.REJECTED, NOW.plusMinutes(4)));
        mapper.insertOrder(inPackage(6, "y:1", DomesticOrderStatus.EXECUTED, NOW));

        assertThat(mapper.selectExecutedByPackageAsc(5L))
                .extracting(StockOrderVO::getIdempotencyKey)
                .containsExactly("x:1", "x:2");
    }

    @Test
    @DisplayName("고객 단위 체결 조회는 내 패키지의 체결만 오래된 순으로 준다")
    void executedByCustomerAsc() {
        mapper.insertOrder(inPackage(6, "m:2", DomesticOrderStatus.EXECUTED, NOW.plusMinutes(2)));
        mapper.insertOrder(inPackage(5, "m:1", DomesticOrderStatus.EXECUTED, NOW));
        mapper.insertOrder(inPackage(5, "m:3", DomesticOrderStatus.REJECTED, NOW.plusMinutes(3)));
        mapper.insertOrder(inPackage(9, "m:4", DomesticOrderStatus.EXECUTED, NOW)); // 남의 패키지

        assertThat(mapper.selectExecutedByCustomerAsc(7L))
                .extracting(StockOrderVO::getIdempotencyKey)
                .containsExactly("m:1", "m:2");
    }

    @Test
    @DisplayName("마지막 매수일은 더 늦은 날짜일 때만 바뀐다")
    void lastPurchaseDateOnlyMovesForward() {
        mapper.insertBalance(
                StockBalanceVO.builder()
                        .accountId(ACCOUNT)
                        .domesticProductId(PRODUCT)
                        .qty(new BigDecimal("10"))
                        .status(DomesticStockStatus.HOLDING)
                        .lastPurchaseDate(LocalDateTime.of(2026, 11, 10, 9, 0))
                        .avgPurchasePrice(new BigDecimal("100000"))
                        .build());
        StockBalanceVO balance = mapper.selectBalanceForUpdate(ACCOUNT, PRODUCT).orElseThrow();

        balance.setLastPurchaseDate(LocalDateTime.of(2026, 6, 10, 9, 0));
        mapper.updateBalance(balance);
        assertThat(
                        mapper.selectBalanceForUpdate(ACCOUNT, PRODUCT)
                                .orElseThrow()
                                .getLastPurchaseDate())
                .isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 0));

        balance.setLastPurchaseDate(LocalDateTime.of(2026, 11, 20, 9, 0));
        mapper.updateBalance(balance);
        assertThat(
                        mapper.selectBalanceForUpdate(ACCOUNT, PRODUCT)
                                .orElseThrow()
                                .getLastPurchaseDate())
                .isEqualTo(LocalDateTime.of(2026, 11, 20, 9, 0));
    }

    @Test
    @DisplayName("보유 종목 조회는 잔량이 있는 것만 준다")
    void heldProducts() throws Exception {
        sql(
                "INSERT INTO domestic_product (domestic_product_id,ticker,name,type) VALUES (2,'000660','SK하이닉스','STOCK')");
        mapper.insertBalance(balance(PRODUCT, "10", DomesticStockStatus.HOLDING));
        mapper.insertBalance(balance(2L, "0", DomesticStockStatus.SOLD_OUT));
        assertThat(mapper.selectHeldProductIds()).containsExactly(PRODUCT);
        assertThat(mapper.selectHeldBalancesByAccount(ACCOUNT))
                .extracting(StockBalanceVO::getDomesticProductId)
                .containsExactly(PRODUCT);
        assertThat(mapper.selectHeldBalancesByAccount(999L)).isEmpty();
    }

    private static StockBalanceVO balance(long productId, String qty, DomesticStockStatus status) {
        return StockBalanceVO.builder()
                .accountId(ACCOUNT)
                .domesticProductId(productId)
                .qty(new BigDecimal(qty))
                .status(status)
                .lastPurchaseDate(NOW)
                .avgPurchasePrice(new BigDecimal("100000"))
                .build();
    }
}
