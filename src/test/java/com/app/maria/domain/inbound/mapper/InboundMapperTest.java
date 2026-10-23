package com.app.maria.domain.inbound.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.inbound.dto.InboundAccountSummaryDTO;
import com.app.maria.domain.inbound.dto.InboundDTO;
import com.app.maria.domain.inbound.dto.InboundDetailDTO;
import com.app.maria.domain.inbound.dto.InboundHoldingDTO;
import com.app.maria.domain.inbound.dto.InboundListDTO;
import com.app.maria.domain.inbound.dto.InboundLotDTO;
import com.app.maria.domain.inbound.dto.InboundMinDTO;
import com.app.maria.domain.inbound.dto.InboundPriorApprovalDTO;
import com.app.maria.domain.inbound.dto.InboundSummaryDTO;
import com.app.maria.domain.inbound.dto.SourceLotApprovedQtyDTO;
import com.app.maria.domain.registrablestock.type.GeneralAccountType;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboundMapperTest {

    private static final LocalDateTime PURCHASE_DATE = LocalDateTime.of(2025, 6, 1, 0, 0);
    private static final BigDecimal PURCHASE_PRICE = BigDecimal.valueOf(150.25);
    private static final BigDecimal PURCHASE_FX_RATE = BigDecimal.valueOf(1320.5);

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private InboundMapper inboundMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader = Resources.getResourceAsReader("mybatis-inbound-test-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
        dataSource =
                (PooledDataSource)
                        sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
    }

    @BeforeEach
    void setUpDatabase() throws SQLException {
        resetSchema();
        sqlSession = sqlSessionFactory.openSession(true);
        inboundMapper = sqlSession.getMapper(InboundMapper.class);
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
    @DisplayName("승인 이력이 전혀 없으면 0을 반환한다")
    void sumReturnsZeroWhenNoApprovalHistoryExists() {
        BigDecimal sum = inboundMapper.sumApprovedQtyByAccountAndProduct(1L, 1L);

        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("동일 계좌·동일 상품의 승인수량을 모두 합산한다")
    void sumAggregatesAllApprovalsForSameAccountAndProduct() {
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(60));
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(25));

        BigDecimal sum = inboundMapper.sumApprovedQtyByAccountAndProduct(1L, 1L);

        assertThat(sum).isEqualByComparingTo(BigDecimal.valueOf(85));
    }

    @Test
    @DisplayName("다른 상품(foreignProductId)의 승인수량은 합산에서 제외한다")
    void sumExcludesApprovalsForDifferentProduct() {
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(60));
        insertApprovedInbound(1L, 2L, BigDecimal.valueOf(999));

        BigDecimal sum = inboundMapper.sumApprovedQtyByAccountAndProduct(1L, 1L);

        assertThat(sum).isEqualByComparingTo(BigDecimal.valueOf(60));
    }

    @Test
    @DisplayName("다른 계좌(accountId)의 승인수량은 합산에서 제외한다")
    void sumExcludesApprovalsForDifferentAccount() {
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(60));
        insertApprovedInbound(2L, 1L, BigDecimal.valueOf(999));

        BigDecimal sum = inboundMapper.sumApprovedQtyByAccountAndProduct(1L, 1L);

        assertThat(sum).isEqualByComparingTo(BigDecimal.valueOf(60));
    }

    @Test
    @DisplayName("이후 매도로 current_qty가 줄어도, 최초 승인수량(qty) 기준으로 합산한다")
    void sumUsesOriginalApprovedQtyNotRemainingQty() {
        Long inboundDetailId = insertApprovedInbound(1L, 1L, BigDecimal.valueOf(60));
        reduceCurrentQty(inboundDetailId, BigDecimal.valueOf(10));

        BigDecimal sum = inboundMapper.sumApprovedQtyByAccountAndProduct(1L, 1L);

        assertThat(sum).isEqualByComparingTo(BigDecimal.valueOf(60));
    }

    @Test
    @DisplayName("두 번째 입고 신청의 가용수량은 기준일 보유수량에서 기존 승인수량을 뺀 값과 일치한다")
    void availableQtyReflectsPreviouslyApprovedQty() {
        BigDecimal snapshotQty = BigDecimal.valueOf(100);
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(60));

        BigDecimal alreadyApprovedQty = inboundMapper.sumApprovedQtyByAccountAndProduct(1L, 1L);
        BigDecimal availableQty = snapshotQty.subtract(alreadyApprovedQty).max(BigDecimal.ZERO);

        assertThat(alreadyApprovedQty).isEqualByComparingTo(BigDecimal.valueOf(60));
        assertThat(availableQty).isEqualByComparingTo(BigDecimal.valueOf(40));
    }

    // ---- sumApprovedQtyBySourceGeneralAccount ----

    @Test
    @DisplayName("출처 일반계좌별로 승인수량을 합산한다")
    void sumApprovedQtyBySourceGeneralAccountGroupsByGeneralAccountId() {
        insertApprovedInboundWithSource(1L, 1L, 10L, BigDecimal.valueOf(40));
        insertApprovedInboundWithSource(1L, 1L, 10L, BigDecimal.valueOf(20));
        insertApprovedInboundWithSource(1L, 1L, 20L, BigDecimal.valueOf(15));

        List<SourceLotApprovedQtyDTO> result =
                inboundMapper.sumApprovedQtyBySourceGeneralAccount(1L, 1L);

        assertThat(result)
                .extracting(SourceLotApprovedQtyDTO::getGeneralAccountId)
                .containsExactlyInAnyOrder(10L, 20L);
        assertThat(
                        result.stream()
                                .filter(dto -> dto.getGeneralAccountId().equals(10L))
                                .findFirst()
                                .orElseThrow()
                                .getApprovedQty())
                .isEqualByComparingTo(BigDecimal.valueOf(60));
        assertThat(
                        result.stream()
                                .filter(dto -> dto.getGeneralAccountId().equals(20L))
                                .findFirst()
                                .orElseThrow()
                                .getApprovedQty())
                .isEqualByComparingTo(BigDecimal.valueOf(15));
    }

    @Test
    @DisplayName("source_general_account_id가 없는 lot(타사대체입고)은 집계에서 제외한다")
    void sumApprovedQtyBySourceGeneralAccountExcludesNullSource() {
        insertApprovedInboundWithSource(1L, 1L, null, BigDecimal.valueOf(40));
        insertApprovedInboundWithSource(1L, 1L, 10L, BigDecimal.valueOf(20));

        List<SourceLotApprovedQtyDTO> result =
                inboundMapper.sumApprovedQtyBySourceGeneralAccount(1L, 1L);

        assertThat(result)
                .extracting(SourceLotApprovedQtyDTO::getGeneralAccountId)
                .containsExactly(10L);
    }

    @Test
    @DisplayName("승인 이력이 없으면 빈 목록을 반환한다")
    void sumApprovedQtyBySourceGeneralAccountReturnsEmptyListWhenNoApprovalHistoryExists() {
        List<SourceLotApprovedQtyDTO> result =
                inboundMapper.sumApprovedQtyBySourceGeneralAccount(1L, 1L);

        assertThat(result).isEmpty();
    }

    // ---- selectFifoLots ----

    @Test
    @DisplayName("여러 lot이 있으면 purchase_date 오래된 순으로 반환한다")
    void selectFifoLotsReturnsLotsOrderedByPurchaseDateAscending() {
        Long newer =
                insertApprovedInbound(1L, 1L, BigDecimal.valueOf(30), PURCHASE_DATE.plusMonths(2));
        Long oldest = insertApprovedInbound(1L, 1L, BigDecimal.valueOf(10), PURCHASE_DATE);
        Long middle =
                insertApprovedInbound(1L, 1L, BigDecimal.valueOf(20), PURCHASE_DATE.plusMonths(1));

        List<InboundDetailDTO> lots = inboundMapper.selectFifoLots(1L, 1L);

        assertThat(lots)
                .extracting(InboundDetailDTO::getInboundDetailId)
                .containsExactly(oldest, middle, newer);
    }

    @Test
    @DisplayName("current_qty가 0인 lot은 제외한다")
    void selectFifoLotsExcludesLotsWithZeroCurrentQty() {
        Long depleted = insertApprovedInbound(1L, 1L, BigDecimal.valueOf(10), PURCHASE_DATE);
        reduceCurrentQty(depleted, BigDecimal.ZERO);
        Long remaining =
                insertApprovedInbound(1L, 1L, BigDecimal.valueOf(20), PURCHASE_DATE.plusMonths(1));

        List<InboundDetailDTO> lots = inboundMapper.selectFifoLots(1L, 1L);

        assertThat(lots)
                .extracting(InboundDetailDTO::getInboundDetailId)
                .containsExactly(remaining);
    }

    @Test
    @DisplayName("다른 계좌·다른 종목의 lot은 제외한다")
    void selectFifoLotsExcludesOtherAccountsAndProducts() {
        insertApprovedInbound(2L, 1L, BigDecimal.valueOf(10), PURCHASE_DATE);
        insertApprovedInbound(1L, 2L, BigDecimal.valueOf(10), PURCHASE_DATE);
        Long matching = insertApprovedInbound(1L, 1L, BigDecimal.valueOf(10), PURCHASE_DATE);

        List<InboundDetailDTO> lots = inboundMapper.selectFifoLots(1L, 1L);

        assertThat(lots).extracting(InboundDetailDTO::getInboundDetailId).containsExactly(matching);
    }

    @Test
    @DisplayName("해당 계좌·종목의 lot이 없으면 빈 목록을 반환한다")
    void selectFifoLotsReturnsEmptyListWhenNoLotsExist() {
        List<InboundDetailDTO> lots = inboundMapper.selectFifoLots(1L, 1L);

        assertThat(lots).isEmpty();
    }

    // ---- selectHoldingsByAccount ----

    @Test
    @DisplayName("동일 종목의 여러 lot은 수량을 합산해 한 건으로 반환한다")
    void selectHoldingsByAccountSumsQtyAcrossLotsOfSameProduct() {
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(30));
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(20));

        List<InboundHoldingDTO> holdings = inboundMapper.selectHoldingsByAccount(1L);

        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).getForeignProductId()).isEqualTo(1L);
        assertThat(holdings.get(0).getCurrentQty()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    @DisplayName("종목이 다르면 각각 별도 행으로 반환한다")
    void selectHoldingsByAccountGroupsByDistinctProduct() {
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(30));
        insertApprovedInbound(1L, 2L, BigDecimal.valueOf(10));

        List<InboundHoldingDTO> holdings = inboundMapper.selectHoldingsByAccount(1L);

        assertThat(holdings)
                .extracting(InboundHoldingDTO::getForeignProductId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("current_qty가 0인 lot만 있는 종목은 결과에서 제외한다")
    void selectHoldingsByAccountExcludesProductWithOnlyZeroCurrentQtyLots() {
        Long depleted = insertApprovedInbound(1L, 1L, BigDecimal.valueOf(30));
        reduceCurrentQty(depleted, BigDecimal.ZERO);
        insertApprovedInbound(1L, 2L, BigDecimal.valueOf(10));

        List<InboundHoldingDTO> holdings = inboundMapper.selectHoldingsByAccount(1L);

        assertThat(holdings).extracting(InboundHoldingDTO::getForeignProductId).containsExactly(2L);
    }

    @Test
    @DisplayName("다른 계좌의 보유종목은 섞이지 않는다")
    void selectHoldingsByAccountExcludesOtherAccounts() {
        insertApprovedInbound(1L, 1L, BigDecimal.valueOf(30));
        insertApprovedInbound(2L, 1L, BigDecimal.valueOf(999));

        List<InboundHoldingDTO> holdings = inboundMapper.selectHoldingsByAccount(1L);

        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).getCurrentQty()).isEqualByComparingTo(BigDecimal.valueOf(30));
    }

    @Test
    @DisplayName("보유종목이 없으면 빈 목록을 반환한다")
    void selectHoldingsByAccountReturnsEmptyListWhenNoHoldingsExist() {
        List<InboundHoldingDTO> holdings = inboundMapper.selectHoldingsByAccount(1L);

        assertThat(holdings).isEmpty();
    }

    // ---- selectInbounds ----

    @Test
    @DisplayName("입고 이력을 고객명·종목정보와 함께 처리일시 최신순으로 조회한다")
    void selectInboundsReturnsListOrderedByProcessedAtDesc() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(80),
                BigDecimal.valueOf(80),
                LocalDateTime.of(2026, 3, 5, 9, 0));
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(50),
                LocalDateTime.of(2026, 3, 6, 9, 0));

        List<InboundListDTO> result = inboundMapper.selectInbounds(0, 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getProcessedAt()).isEqualTo(LocalDateTime.of(2026, 3, 6, 9, 0));
        assertThat(result.get(1).getProcessedAt()).isEqualTo(LocalDateTime.of(2026, 3, 5, 9, 0));
        assertThat(result.get(0).getCustomerName()).isEqualTo("홍길동");
        assertThat(result.get(0).getTicker()).isEqualTo("AAPL");
        assertThat(result.get(0).getProductName()).isEqualTo("Apple Inc.");
        assertThat(result.get(0).getAccountNo()).isEqualTo("1234567890");
    }

    @Test
    @DisplayName("입고 1건에 lot이 여러 개(멀티 출처계좌)여도 목록에는 1행만 나온다")
    void selectInboundsReturnsOneRowPerInboundEvenWithMultipleLots() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertMultiLotInbound(
                1L,
                1L,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(100),
                LocalDateTime.of(2026, 3, 5, 9, 0),
                BigDecimal.valueOf(40),
                BigDecimal.valueOf(60));

        List<InboundListDTO> result = inboundMapper.selectInbounds(0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTicker()).isEqualTo("AAPL");
    }

    @Test
    @DisplayName("잔여 가능 수량은 12.23 기준수량에서 동일 계좌·종목의 누적 승인수량을 뺀 값이다")
    void selectInboundsReturnsRemainingQtyBasedOnCumulativeApprovals() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(30),
                LocalDateTime.of(2026, 3, 5, 9, 0));
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(70),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(20),
                LocalDateTime.of(2026, 3, 6, 9, 0));

        List<InboundListDTO> result = inboundMapper.selectInbounds(0, 20);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRemainingQty()).isEqualByComparingTo("50");
        assertThat(result.get(1).getRemainingQty()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("3-way MIN 계산 근거(신청수량/기준일수량/현재보유수량/승인수량)를 그대로 반환한다")
    void selectInboundsReturnsThreeWayMinFields() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(90),
                BigDecimal.valueOf(80),
                BigDecimal.valueOf(80),
                LocalDateTime.of(2026, 3, 5, 9, 0));

        List<InboundListDTO> result = inboundMapper.selectInbounds(0, 20);

        assertThat(result).hasSize(1);
        InboundListDTO dto = result.get(0);
        assertThat(dto.getRequestedQty()).isEqualByComparingTo("100");
        assertThat(dto.getCurrentHoldingAtRequest()).isEqualByComparingTo("90");
        assertThat(dto.getSnapshotQty()).isEqualByComparingTo("80");
        assertThat(dto.getApprovedQty()).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("입고 이력이 없으면 빈 목록을 반환한다")
    void selectInboundsReturnsEmptyListWhenNoInboundsExist() {
        List<InboundListDTO> result = inboundMapper.selectInbounds(0, 20);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("size만큼만 조회하고, offset을 지정하면 그만큼 건너뛴 뒤부터 조회한다")
    void selectInboundsAppliesOffsetAndSizeForPagination() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 5, 9, 0));
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                LocalDateTime.of(2026, 3, 6, 9, 0));
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                LocalDateTime.of(2026, 3, 7, 9, 0));

        List<InboundListDTO> firstPage = inboundMapper.selectInbounds(0, 2);
        List<InboundListDTO> secondPage = inboundMapper.selectInbounds(2, 2);

        assertThat(firstPage).hasSize(2);
        assertThat(firstPage.get(0).getProcessedAt()).isEqualTo(LocalDateTime.of(2026, 3, 7, 9, 0));
        assertThat(firstPage.get(1).getProcessedAt()).isEqualTo(LocalDateTime.of(2026, 3, 6, 9, 0));
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).getProcessedAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 5, 9, 0));
    }

    @Test
    @DisplayName("countInbounds는 전체 입고 이력 건수를 반환한다")
    void countInboundsReturnsTotalRowCount() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 5, 9, 0));
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                LocalDateTime.of(2026, 3, 6, 9, 0));

        int count = inboundMapper.countInbounds();

        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("countInbounds는 입고 이력이 없으면 0을 반환한다")
    void countInboundsReturnsZeroWhenNoInboundsExist() {
        int count = inboundMapper.countInbounds();

        assertThat(count).isEqualTo(0);
    }

    // ---- selectLotsByInboundIds ----

    @Test
    @DisplayName("여러 inboundId를 한번에 조회하면 각 입고별 lot으로 정확히 묶인다")
    void selectLotsByInboundIdsGroupsLotsByInboundId() {
        Long inbound1 =
                insertMultiLotInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(100),
                        LocalDateTime.of(2026, 3, 5, 9, 0),
                        BigDecimal.valueOf(40),
                        BigDecimal.valueOf(60));
        Long inbound2 =
                insertMultiLotInbound(
                        2L,
                        1L,
                        BigDecimal.valueOf(30),
                        BigDecimal.valueOf(30),
                        LocalDateTime.of(2026, 3, 6, 9, 0),
                        BigDecimal.valueOf(30));

        List<InboundLotDTO> result =
                inboundMapper.selectLotsByInboundIds(List.of(inbound1, inbound2));

        assertThat(result).hasSize(3);
        assertThat(result.stream().filter(lot -> lot.getInboundId().equals(inbound1)).count())
                .isEqualTo(2);
        assertThat(result.stream().filter(lot -> lot.getInboundId().equals(inbound2)).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("recordedAt·매수 정보를 그대로 반환한다")
    void selectLotsByInboundIdsReturnsRecordedAtAndPurchaseFields() {
        Long inboundId =
                insertMultiLotInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(50),
                        LocalDateTime.of(2026, 3, 5, 9, 0),
                        BigDecimal.valueOf(50));

        List<InboundLotDTO> result = inboundMapper.selectLotsByInboundIds(List.of(inboundId));

        assertThat(result).hasSize(1);
        InboundLotDTO lot = result.get(0);
        assertThat(lot.getRecordedAt()).isEqualTo(PURCHASE_DATE);
        assertThat(lot.getPurchaseDate()).isEqualTo(PURCHASE_DATE);
        assertThat(lot.getPurchasePrice()).isEqualByComparingTo(PURCHASE_PRICE);
        assertThat(lot.getPurchaseCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("account_type을 저장한 그대로 반환한다")
    void selectLotsByInboundIdsReturnsAccountType() throws SQLException {
        Long inboundId =
                insertMultiLotInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(50),
                        LocalDateTime.of(2026, 3, 5, 9, 0),
                        BigDecimal.valueOf(50));
        setAccountType(inboundId, "IRP");

        List<InboundLotDTO> result = inboundMapper.selectLotsByInboundIds(List.of(inboundId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAccountType()).isEqualTo(GeneralAccountType.IRP);
    }

    @Test
    @DisplayName("일치하는 inboundId가 없으면 빈 목록을 반환한다")
    void selectLotsByInboundIdsReturnsEmptyListWhenNoMatchingInboundIds() {
        List<InboundLotDTO> result = inboundMapper.selectLotsByInboundIds(List.of(999L));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("오늘 처리된 건만 집계하고, 반려·감액 건수를 구분해서 센다")
    void selectTodaySummaryCountsTodaysInboundsAndClassifiesRejectedAndReduced()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        LocalDateTime today = LocalDateTime.of(2026, 3, 10, 9, 0);
        LocalDateTime yesterday = LocalDateTime.of(2026, 3, 9, 9, 0);

        // 전량승인
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(50),
                today);
        // 반려(0주 승인)
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(30),
                BigDecimal.ZERO,
                BigDecimal.valueOf(30),
                BigDecimal.ZERO,
                today);
        // 감액(부분승인)
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(40),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(40),
                BigDecimal.valueOf(20),
                today);
        // 어제 처리분 - 오늘 집계에서 제외돼야 함
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                yesterday);

        InboundSummaryDTO result =
                inboundMapper.selectTodaySummary(
                        LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11));

        assertThat(result.getTodayProcessedCount()).isEqualTo(3);
        assertThat(result.getTodayRejectedCount()).isEqualTo(1);
        assertThat(result.getTodayReducedCount()).isEqualTo(1);
        assertThat(result.getTodayApprovedQtySum()).isEqualByComparingTo(BigDecimal.valueOf(70));
    }

    @Test
    @DisplayName("오늘 처리된 건이 없으면 전부 0을 반환한다")
    void selectTodaySummaryReturnsZerosWhenNoInboundsToday() {
        InboundSummaryDTO result =
                inboundMapper.selectTodaySummary(
                        LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11));

        assertThat(result.getTodayProcessedCount()).isEqualTo(0);
        assertThat(result.getTodayRejectedCount()).isEqualTo(0);
        assertThat(result.getTodayReducedCount()).isEqualTo(0);
        assertThat(result.getTodayApprovedQtySum()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("계좌별 입고 이력을 최근 처리일시 내림차순으로 반환하고, 계좌별 건수를 집계한다")
    void selectAccountsWithInboundsReturnsAccountsOrderedByLastProcessedAtDesc()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1234567890");
        insertAccount(2L, 2L, "9876543210");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertForeignProduct(2L, "MSFT", "Microsoft");

        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        insertFullInbound(
                1L,
                2L,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                LocalDateTime.of(2026, 3, 5, 9, 0));
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                LocalDateTime.of(2026, 3, 3, 9, 0));

        List<InboundAccountSummaryDTO> result =
                inboundMapper.selectAccountsWithInbounds(0, 10, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAccountId()).isEqualTo(1L);
        assertThat(result.get(0).getInboundCount()).isEqualTo(2);
        assertThat(result.get(0).getLastProcessedAt())
                .isEqualTo(LocalDateTime.of(2026, 3, 5, 9, 0));
        assertThat(result.get(1).getAccountId()).isEqualTo(2L);
        assertThat(result.get(1).getInboundCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("size만큼만 조회하고, offset을 지정하면 그만큼 건너뛴 뒤부터 조회한다")
    void selectAccountsWithInboundsAppliesOffsetAndSizeForPagination() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertCustomer(3L, "이영희");
        insertAccount(1L, 1L, "1111111111");
        insertAccount(2L, 2L, "2222222222");
        insertAccount(3L, 3L, "3333333333");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 2, 9, 0));
        insertFullInbound(
                3L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 3, 9, 0));

        List<InboundAccountSummaryDTO> result =
                inboundMapper.selectAccountsWithInbounds(1, 1, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAccountId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("입고 이력이 없으면 빈 목록을 반환한다")
    void selectAccountsWithInboundsReturnsEmptyListWhenNoInboundsExist() {
        List<InboundAccountSummaryDTO> result =
                inboundMapper.selectAccountsWithInbounds(0, 10, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("keyword가 고객명에 부분일치하면 해당 계좌만 반환한다")
    void selectAccountsWithInboundsFiltersByCustomerNameKeyword() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111");
        insertAccount(2L, 2L, "2222222222");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 2, 9, 0));

        List<InboundAccountSummaryDTO> result =
                inboundMapper.selectAccountsWithInbounds(0, 10, "홍길동");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAccountId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("keyword가 계좌번호에 부분일치하면 해당 계좌만 반환한다")
    void selectAccountsWithInboundsFiltersByAccountNoKeyword() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111");
        insertAccount(2L, 2L, "2222222222");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 2, 9, 0));

        List<InboundAccountSummaryDTO> result =
                inboundMapper.selectAccountsWithInbounds(0, 10, "2222");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAccountId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("keyword와 일치하는 계좌가 없으면 빈 목록을 반환한다")
    void selectAccountsWithInboundsReturnsEmptyListWhenKeywordMatchesNothing() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1111111111");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));

        List<InboundAccountSummaryDTO> result =
                inboundMapper.selectAccountsWithInbounds(0, 10, "존재하지않음");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("입고 이력이 있는 계좌 수를 반환한다")
    void countAccountsWithInboundsReturnsDistinctAccountCount() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                LocalDateTime.of(2026, 3, 2, 9, 0));

        int result = inboundMapper.countAccountsWithInbounds(null);

        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("입고 이력이 없으면 0을 반환한다")
    void countAccountsWithInboundsReturnsZeroWhenNoInboundsExist() {
        int result = inboundMapper.countAccountsWithInbounds(null);

        assertThat(result).isEqualTo(0);
    }

    @Test
    @DisplayName("keyword와 일치하는 계좌만 센다")
    void countAccountsWithInboundsFiltersByKeyword() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1111111111");
        insertAccount(2L, 2L, "2222222222");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 2, 9, 0));

        int result = inboundMapper.countAccountsWithInbounds("홍길동");

        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("해당 계좌의 입고 이력만 처리일시 최신순으로 반환한다")
    void selectInboundsByAccountIdReturnsOnlyMatchingAccountOrderedByProcessedAtDesc()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1234567890");
        insertAccount(2L, 2L, "9876543210");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        Long earlier =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(10),
                        LocalDateTime.of(2026, 3, 1, 9, 0));
        Long later =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(20),
                        LocalDateTime.of(2026, 3, 5, 9, 0));
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                LocalDateTime.of(2026, 3, 3, 9, 0));

        List<InboundListDTO> result = inboundMapper.selectInboundsByAccountId(1L, 0, 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getInboundId()).isEqualTo(later);
        assertThat(result.get(1).getInboundId()).isEqualTo(earlier);
    }

    @Test
    @DisplayName("일치하는 계좌가 없으면 빈 목록을 반환한다")
    void selectInboundsByAccountIdReturnsEmptyListWhenNoMatchingAccount() {
        List<InboundListDTO> result = inboundMapper.selectInboundsByAccountId(999L, 0, 10);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("countInboundsByAccountId는 해당 계좌의 입고 건수만 센다")
    void countInboundsByAccountIdReturnsCountForThatAccountOnly() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1234567890");
        insertAccount(2L, 2L, "9876543210");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        insertFullInbound(
                1L,
                1L,
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                BigDecimal.valueOf(20),
                LocalDateTime.of(2026, 3, 2, 9, 0));
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                BigDecimal.valueOf(30),
                LocalDateTime.of(2026, 3, 3, 9, 0));

        int result = inboundMapper.countInboundsByAccountId(1L);

        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 계좌·같은 종목의 과거 입고 건을 처리일시 오름차순으로 반환한다")
    void selectPriorApprovalsReturnsOtherInboundsForSameAccountAndProductOrderedAscending()
            throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        Long first =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(10),
                        LocalDateTime.of(2026, 3, 1, 9, 0));
        Long second =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(15),
                        BigDecimal.valueOf(15),
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(15),
                        LocalDateTime.of(2026, 3, 3, 9, 0));
        Long latest =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(20),
                        LocalDateTime.of(2026, 3, 5, 9, 0));

        List<InboundPriorApprovalDTO> result = inboundMapper.selectPriorApprovals(latest);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getInboundId()).isEqualTo(first);
        assertThat(result.get(0).getApprovedQty()).isEqualByComparingTo(BigDecimal.valueOf(10));
        assertThat(result.get(1).getInboundId()).isEqualTo(second);
        assertThat(result.get(1).getApprovedQty()).isEqualByComparingTo(BigDecimal.valueOf(15));
    }

    @Test
    @DisplayName("다른 종목의 입고 건은 제외한다")
    void selectPriorApprovalsExcludesDifferentProduct() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertForeignProduct(2L, "MSFT", "Microsoft");
        insertFullInbound(
                1L,
                2L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        Long target =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(20),
                        LocalDateTime.of(2026, 3, 5, 9, 0));

        List<InboundPriorApprovalDTO> result = inboundMapper.selectPriorApprovals(target);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("다른 계좌의 입고 건은 제외한다")
    void selectPriorApprovalsExcludesDifferentAccount() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertCustomer(2L, "김철수");
        insertAccount(1L, 1L, "1234567890");
        insertAccount(2L, 2L, "9876543210");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        insertFullInbound(
                2L,
                1L,
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(50),
                BigDecimal.valueOf(10),
                LocalDateTime.of(2026, 3, 1, 9, 0));
        Long target =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(20),
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(20),
                        LocalDateTime.of(2026, 3, 5, 9, 0));

        List<InboundPriorApprovalDTO> result = inboundMapper.selectPriorApprovals(target);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("과거 입고 건이 없으면 빈 목록을 반환한다")
    void selectPriorApprovalsReturnsEmptyListWhenNoPriorApprovalsExist() throws SQLException {
        insertCustomer(1L, "홍길동");
        insertAccount(1L, 1L, "1234567890");
        insertForeignProduct(1L, "AAPL", "Apple Inc.");
        Long only =
                insertFullInbound(
                        1L,
                        1L,
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(50),
                        BigDecimal.valueOf(10),
                        LocalDateTime.of(2026, 3, 1, 9, 0));

        List<InboundPriorApprovalDTO> result = inboundMapper.selectPriorApprovals(only);

        assertThat(result).isEmpty();
    }

    private void insertCustomer(Long customerId, String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO customer (customer_id, name) VALUES ("
                            + customerId
                            + ", '"
                            + name
                            + "')");
        }
    }

    private void insertAccount(Long accountId, Long customerId, String accountNo)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO account (account_id, customer_id, account_no) VALUES ("
                            + accountId
                            + ", "
                            + customerId
                            + ", '"
                            + accountNo
                            + "')");
        }
    }

    private void insertForeignProduct(Long foreignProductId, String ticker, String name)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO foreign_product (foreign_product_id, ticker, name) VALUES ("
                            + foreignProductId
                            + ", '"
                            + ticker
                            + "', '"
                            + name
                            + "')");
        }
    }

    private Long insertFullInbound(
            Long accountId,
            Long foreignProductId,
            BigDecimal requestedQty,
            BigDecimal currentHoldingAtRequest,
            BigDecimal snapshotQty,
            BigDecimal approvedQty,
            LocalDateTime processedAt) {
        InboundDTO inboundDTO =
                InboundDTO.builder()
                        .accountId(accountId)
                        .requestedQty(requestedQty)
                        .currentHoldingAtRequest(currentHoldingAtRequest)
                        .approvedQty(approvedQty)
                        .processedAt(processedAt)
                        .build();
        inboundMapper.insertInbound(inboundDTO);

        InboundDetailDTO inboundDetailDTO =
                InboundDetailDTO.builder()
                        .inboundId(inboundDTO.getInboundId())
                        .foreignProductId(foreignProductId)
                        .qty(approvedQty)
                        .currentQty(approvedQty)
                        .recordedAt(PURCHASE_DATE)
                        .purchaseDate(PURCHASE_DATE)
                        .purchasePrice(PURCHASE_PRICE)
                        .purchaseCurrency("USD")
                        .purchaseFxRate(PURCHASE_FX_RATE)
                        .sourceGeneralAccountId(accountId)
                        .build();
        inboundMapper.insertInboundDetail(inboundDetailDTO);

        InboundMinDTO inboundMinDTO =
                InboundMinDTO.of(
                        inboundDetailDTO.getInboundDetailId(),
                        requestedQty,
                        approvedQty,
                        snapshotQty);
        inboundMapper.insertInboundMin(inboundMinDTO);

        return inboundDTO.getInboundId();
    }

    private Long insertMultiLotInbound(
            Long accountId,
            Long foreignProductId,
            BigDecimal requestedQty,
            BigDecimal approvedQty,
            LocalDateTime processedAt,
            BigDecimal... lotQtys) {
        InboundDTO inboundDTO =
                InboundDTO.builder()
                        .accountId(accountId)
                        .requestedQty(requestedQty)
                        .currentHoldingAtRequest(requestedQty)
                        .approvedQty(approvedQty)
                        .processedAt(processedAt)
                        .build();
        inboundMapper.insertInbound(inboundDTO);

        for (BigDecimal lotQty : lotQtys) {
            InboundDetailDTO inboundDetailDTO =
                    InboundDetailDTO.builder()
                            .inboundId(inboundDTO.getInboundId())
                            .foreignProductId(foreignProductId)
                            .qty(lotQty)
                            .currentQty(lotQty)
                            .recordedAt(PURCHASE_DATE)
                            .purchaseDate(PURCHASE_DATE)
                            .purchasePrice(PURCHASE_PRICE)
                            .purchaseCurrency("USD")
                            .purchaseFxRate(PURCHASE_FX_RATE)
                            .sourceGeneralAccountId(accountId)
                            .build();
            inboundMapper.insertInboundDetail(inboundDetailDTO);

            InboundMinDTO inboundMinDTO =
                    InboundMinDTO.of(
                            inboundDetailDTO.getInboundDetailId(),
                            requestedQty,
                            lotQty,
                            approvedQty);
            inboundMapper.insertInboundMin(inboundMinDTO);
        }

        return inboundDTO.getInboundId();
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
          CREATE TABLE inbound (
              inbound_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              account_id BIGINT NOT NULL,
              requested_qty DECIMAL(15, 4) NOT NULL,
              current_holding_at_request DECIMAL(15, 4),
              approved_qty DECIMAL(15, 4) NOT NULL,
              processed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
          )
          """);
            statement.execute(
                    """
          CREATE TABLE inbound_detail (
              inbound_detail_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              inbound_id BIGINT NOT NULL,
              foreign_product_id BIGINT NOT NULL,
              registrable_stock_id BIGINT,
              source_broker VARCHAR(50),
              qty DECIMAL(15, 4) NOT NULL,
              current_qty DECIMAL(15, 4) NOT NULL,
              purchase_date DATETIME NOT NULL,
              purchase_price DECIMAL(15, 4) NOT NULL,
              purchase_currency VARCHAR(10) NOT NULL,
              purchase_fx_rate DECIMAL(15, 4) NOT NULL,
              source_general_account_id BIGINT,
              account_type VARCHAR(30),
              recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              CONSTRAINT fk_inbound_detail_inbound FOREIGN KEY (inbound_id)
                  REFERENCES inbound(inbound_id)
          )
          """);
            statement.execute(
                    """
          CREATE TABLE inbound_min (
              inbound_min_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              inbound_detail_id BIGINT NOT NULL,
              requested_qty DECIMAL(15, 4) NOT NULL,
              approved_qty DECIMAL(15, 4) NOT NULL,
              snapshot_qty DECIMAL(15, 4) NOT NULL
          )
          """);
            statement.execute(
                    """
          CREATE TABLE customer (
              customer_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              name VARCHAR(50) NOT NULL
          )
          """);
            statement.execute(
                    """
          CREATE TABLE account (
              account_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              customer_id BIGINT NOT NULL,
              account_no VARCHAR(10)
          )
          """);
            statement.execute(
                    """
          CREATE TABLE foreign_product (
              foreign_product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
              ticker VARCHAR(20) NOT NULL,
              name VARCHAR(100) NOT NULL
          )
          """);
        }
    }

    private Long insertApprovedInbound(
            Long accountId, Long foreignProductId, BigDecimal approvedQty) {
        return insertApprovedInbound(accountId, foreignProductId, approvedQty, PURCHASE_DATE);
    }

    private Long insertApprovedInbound(
            Long accountId,
            Long foreignProductId,
            BigDecimal approvedQty,
            LocalDateTime purchaseDate) {
        InboundDTO inboundDTO =
                InboundDTO.builder()
                        .accountId(accountId)
                        .requestedQty(approvedQty)
                        .currentHoldingAtRequest(approvedQty)
                        .approvedQty(approvedQty)
                        .processedAt(PURCHASE_DATE)
                        .build();
        inboundMapper.insertInbound(inboundDTO);

        InboundDetailDTO inboundDetailDTO =
                InboundDetailDTO.builder()
                        .inboundId(inboundDTO.getInboundId())
                        .foreignProductId(foreignProductId)
                        .qty(approvedQty)
                        .currentQty(approvedQty)
                        .recordedAt(purchaseDate)
                        .purchaseDate(purchaseDate)
                        .purchasePrice(PURCHASE_PRICE)
                        .purchaseCurrency("USD")
                        .purchaseFxRate(PURCHASE_FX_RATE)
                        .sourceGeneralAccountId(accountId)
                        .build();
        inboundMapper.insertInboundDetail(inboundDetailDTO);

        return inboundDetailDTO.getInboundDetailId();
    }

    private Long insertApprovedInboundWithSource(
            Long accountId,
            Long foreignProductId,
            Long sourceGeneralAccountId,
            BigDecimal approvedQty) {
        InboundDTO inboundDTO =
                InboundDTO.builder()
                        .accountId(accountId)
                        .requestedQty(approvedQty)
                        .currentHoldingAtRequest(approvedQty)
                        .approvedQty(approvedQty)
                        .processedAt(PURCHASE_DATE)
                        .build();
        inboundMapper.insertInbound(inboundDTO);

        InboundDetailDTO inboundDetailDTO =
                InboundDetailDTO.builder()
                        .inboundId(inboundDTO.getInboundId())
                        .foreignProductId(foreignProductId)
                        .qty(approvedQty)
                        .currentQty(approvedQty)
                        .recordedAt(PURCHASE_DATE)
                        .purchaseDate(PURCHASE_DATE)
                        .purchasePrice(PURCHASE_PRICE)
                        .purchaseCurrency("USD")
                        .purchaseFxRate(PURCHASE_FX_RATE)
                        .sourceGeneralAccountId(sourceGeneralAccountId)
                        .build();
        inboundMapper.insertInboundDetail(inboundDetailDTO);

        return inboundDetailDTO.getInboundDetailId();
    }

    private void setAccountType(Long inboundId, String accountType) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE inbound_detail SET account_type = ? WHERE inbound_id = ?")) {
            statement.setString(1, accountType);
            statement.setLong(2, inboundId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void reduceCurrentQty(Long inboundDetailId, BigDecimal newCurrentQty) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE inbound_detail SET current_qty = ? WHERE inbound_detail_id = ?")) {
            statement.setBigDecimal(1, newCurrentQty);
            statement.setLong(2, inboundDetailId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
