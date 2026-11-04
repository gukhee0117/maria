package com.app.maria.domain.stockpackage.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.domestic.type.Type;
import com.app.maria.domain.stockpackage.vo.StockPackageItemVO;
import com.app.maria.domain.stockpackage.vo.StockPackageVO;
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

/** 원본 DDL의 관련 테이블과 실제 XML을 H2 MySQL 모드에서 실행한다. 실 MariaDB/FK 전체 검증과는 구분한다. */
class StockPackageMapperTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final long CUSTOMER = 1L;

    private Connection connection;
    private StockPackageMapper mapper;

    @BeforeEach
    void setup() throws Exception {
        String url =
                "jdbc:h2:mem:stockpackage_"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        connection = DriverManager.getConnection(url, "sa", "");
        String ddl = Files.readString(Path.of("db/01_ria_ddl.sql"));
        for (String table :
                List.of(
                        "domestic_product",
                        "domestic_order",
                        "stock_package",
                        "stock_package_item",
                        "stock_theme",
                        "domestic_product_theme")) {
            var matcher =
                    Pattern.compile("(?ms)^CREATE TABLE " + table + " \\(.*?^\\) ENGINE=.*?;")
                            .matcher(ddl);
            assertThat(matcher.find()).as("원본 DDL 테이블: %s", table).isTrue();
            sql(matcher.group().replaceFirst("(?s)\\s+ENGINE=.*$", ";"));
        }
        sql(
                "INSERT INTO domestic_product (domestic_product_id,ticker,name,type) VALUES (1,'005930','삼성전자','STOCK'),(2,'000660','SK하이닉스','STOCK')");

        var dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        var config =
                new Configuration(
                        new Environment(
                                "stockpackage-test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        String resource = "mappers/stockpackage/StockPackageMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(StockPackageMapper.class);
    }

    private void sql(String text) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(text);
        }
    }

    private StockPackageVO newPackage(long customerId, String name) {
        StockPackageVO pkg =
                StockPackageVO.builder()
                        .customerId(customerId)
                        .name(name)
                        .totalAmount(new BigDecimal("5000000"))
                        .createdAt(NOW)
                        .updatedAt(NOW)
                        .build();
        mapper.insertPackage(pkg);
        return pkg;
    }

    private StockPackageItemVO item(long productId, String weight, LocalDateTime confirmedAt) {
        return StockPackageItemVO.builder()
                .domesticProductId(productId)
                .weight(new BigDecimal(weight))
                .unsuitableConfirmedAt(confirmedAt)
                .createdAt(NOW)
                .build();
    }

    @Test
    @DisplayName("패키지를 넣으면 생성된 ID가 채워지고 구성 종목과 함께 읽힌다")
    void insertAndSelect() {
        StockPackageVO pkg = newPackage(CUSTOMER, "내 패키지");
        assertThat(pkg.getStockPackageId()).isNotNull();

        mapper.insertItems(
                pkg.getStockPackageId(), List.of(item(1L, "60.50", null), item(2L, "39.50", NOW)));

        StockPackageVO read =
                mapper.selectByIdAndCustomerId(pkg.getStockPackageId(), CUSTOMER).orElseThrow();
        assertThat(read.getName()).isEqualTo("내 패키지");
        assertThat(read.getTotalAmount()).isEqualByComparingTo("5000000");

        List<StockPackageItemVO> items = mapper.selectItemsByPackageId(pkg.getStockPackageId());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getTicker()).isEqualTo("005930");
        assertThat(items.get(0).getWeight()).isEqualByComparingTo("60.50");
        assertThat(items.get(0).getUnsuitableConfirmedAt()).isNull();
        assertThat(items.get(1).getUnsuitableConfirmedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("남의 패키지는 조회되지 않고, 잠금 조회와 삭제도 마찬가지다")
    void ownerOnly() {
        StockPackageVO pkg = newPackage(CUSTOMER, "내 패키지");

        assertThat(mapper.selectByIdAndCustomerId(pkg.getStockPackageId(), 2L)).isEmpty();
        assertThat(mapper.selectByIdAndCustomerIdForUpdate(pkg.getStockPackageId(), 2L)).isEmpty();
        assertThat(mapper.selectByIdAndCustomerIdForUpdate(pkg.getStockPackageId(), CUSTOMER))
                .isPresent();
        assertThat(mapper.deletePackage(pkg.getStockPackageId(), 2L)).isZero();
    }

    @Test
    @DisplayName("목록은 내 패키지만 최신순으로 종목 수와 함께 준다")
    void summaries() {
        StockPackageVO first = newPackage(CUSTOMER, "첫째");
        mapper.insertItems(first.getStockPackageId(), List.of(item(1L, "100", null)));
        StockPackageVO second = newPackage(CUSTOMER, "둘째");
        newPackage(2L, "남의 것");

        List<StockPackageVO> list = mapper.selectSummariesByCustomerId(CUSTOMER);

        assertThat(list).extracting(StockPackageVO::getName).containsExactly("둘째", "첫째");
        assertThat(list.get(0).getItemCount()).isZero();
        assertThat(list.get(1).getItemCount()).isEqualTo(1);
        assertThat(second.getStockPackageId()).isNotNull();
    }

    @Test
    @DisplayName("수정은 이름·금액·수정시각을 바꾸고 구성은 지우고 다시 넣을 수 있다")
    void updateAndReplaceItems() {
        StockPackageVO pkg = newPackage(CUSTOMER, "처음");
        mapper.insertItems(pkg.getStockPackageId(), List.of(item(1L, "100", null)));

        LocalDateTime later = NOW.plusDays(1);
        pkg.setName("바꿈");
        pkg.setTotalAmount(null);
        pkg.setUpdatedAt(later);
        assertThat(mapper.updatePackage(pkg)).isEqualTo(1);
        mapper.deleteItemsByPackageId(pkg.getStockPackageId());
        mapper.insertItems(
                pkg.getStockPackageId(), List.of(item(1L, "70", null), item(2L, "30", NOW)));

        StockPackageVO read =
                mapper.selectByIdAndCustomerId(pkg.getStockPackageId(), CUSTOMER).orElseThrow();
        assertThat(read.getName()).isEqualTo("바꿈");
        assertThat(read.getTotalAmount()).isNull();
        assertThat(read.getUpdatedAt()).isEqualTo(later);
        assertThat(mapper.selectItemsByPackageId(pkg.getStockPackageId())).hasSize(2);
    }

    @Test
    @DisplayName("같은 종목을 두 번 넣으면 유니크 제약으로 막힌다")
    void duplicateProductBlocked() {
        StockPackageVO pkg = newPackage(CUSTOMER, "중복");
        List<StockPackageItemVO> dup = List.of(item(1L, "50", null), item(1L, "50", null));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mapper.insertItems(pkg.getStockPackageId(), dup))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("종목의 업종과 소속 테마를 읽는다")
    void sectorAndThemes() throws Exception {
        sql("UPDATE domestic_product SET sector_name = '전기전자' WHERE domestic_product_id = 1");
        sql("INSERT INTO stock_theme (stock_theme_id,name) VALUES (1,'반도체'),(2,'2차전지')");
        sql(
                "INSERT INTO domestic_product_theme (domestic_product_id,stock_theme_id) VALUES (1,1),(2,2),(2,1)");

        var products = mapper.selectProductsByIds(List.of(1L, 2L));
        assertThat(products.get(0).getSectorName()).isEqualTo("전기전자");
        assertThat(products.get(1).getSectorName()).isNull();

        var themes = mapper.selectThemesByProductIds(List.of(1L, 2L));
        assertThat(themes)
                .extracting(t -> t.getDomesticProductId() + ":" + t.getThemeName())
                .containsExactly("1:반도체", "2:2차전지", "2:반도체");
        assertThat(mapper.selectThemesByProductIds(List.of(3L))).isEmpty();
    }

    @Test
    @DisplayName("종목 조회는 판정에 필요한 값을 읽고, 삭제 전 주문 수를 센다")
    void productsAndOrderCount() throws Exception {
        var products = mapper.selectProductsByIds(List.of(1L, 2L));
        assertThat(products).hasSize(2);
        assertThat(products.get(0).getTicker()).isEqualTo("005930");
        assertThat(products.get(0).getType()).isEqualTo(Type.STOCK);

        StockPackageVO empty = newPackage(CUSTOMER, "주문없음");
        assertThat(mapper.countOrdersByPackageId(empty.getStockPackageId())).isZero();

        // 같은 세션의 조회 캐시를 피하려고 주문 있는 패키지는 따로 만든다.
        StockPackageVO pkg = newPackage(CUSTOMER, "주문있음");
        sql(
                "INSERT INTO domestic_order (account_id,domestic_product_id,stock_package_id,side,qty,price,amount,channel,idempotency_key,status,processed_at) VALUES (1,1,"
                        + pkg.getStockPackageId()
                        + ",'BUY',1,100,-100,'CUSTOMER','k1','EXECUTED','2026-11-03 10:00:00')");
        assertThat(mapper.countOrdersByPackageId(pkg.getStockPackageId())).isEqualTo(1);
    }
}
