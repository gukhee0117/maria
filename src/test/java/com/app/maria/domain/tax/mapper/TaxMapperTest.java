package com.app.maria.domain.tax.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.maria.domain.tax.dto.ExternalBuyDTO;
import com.app.maria.domain.tax.dto.SellLotDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxRuleDTO;
import com.app.maria.domain.tax.fixture.TaxTestFixture;
import com.app.maria.domain.tax.type.TaxBasisType;
import com.app.maria.domain.tax.type.TaxRuleType;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.exceptions.PersistenceException;
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

class TaxMapperTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final Long OTHER_ACCOUNT_ID = 2L;
    private static final int TAX_YEAR = 2026;
    private static final LocalDateTime CALC_BASE = LocalDateTime.of(2026, 12, 31, 23, 59, 59);

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static TaxTestFixture fixture;

    private SqlSession sqlSession;
    private TaxMapper taxMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader = Resources.getResourceAsReader("mybatis-tax-test-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
        dataSource =
                (PooledDataSource)
                        sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
        fixture = new TaxTestFixture(dataSource);
    }

    @BeforeEach
    void setUpDatabase() throws SQLException {
        fixture.resetSchema();
        sqlSession = sqlSessionFactory.openSession(true);
        taxMapper = sqlSession.getMapper(TaxMapper.class);
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
    @DisplayName("규칙은 종류·기간 제한 없이 전량 조회된다")
    void findTaxRules_전량조회() {
        fixture.insertSeedTaxRules();

        List<TaxRuleDTO> rules = taxMapper.findTaxRules();

        assertThat(rules).hasSize(7);
        assertThat(rules)
                .extracting(TaxRuleDTO::getRuleType)
                .containsExactlyInAnyOrder(
                        TaxRuleType.DEPOSIT_LIMIT,
                        TaxRuleType.HOLDING_PERIOD,
                        TaxRuleType.RELIEF_RATE,
                        TaxRuleType.RELIEF_RATE,
                        TaxRuleType.RELIEF_RATE,
                        TaxRuleType.BASIC_DEDUCTION,
                        TaxRuleType.TAX_RATE);
    }

    @Test
    @DisplayName("valid_to가 9999-12-31인 규칙도 누락 없이 조회된다")
    void findTaxRules_열린구간규칙도_조회된다() {
        fixture.insertSeedTaxRules();

        List<TaxRuleDTO> rules = taxMapper.findTaxRules();

        assertThat(rules)
                .filteredOn(rule -> TaxRuleType.BASIC_DEDUCTION == rule.getRuleType())
                .singleElement()
                .satisfies(
                        rule -> {
                            assertThat(rule.getRuleValue()).isEqualByComparingTo("2500000");
                            assertThat(rule.getValidTo()).isEqualTo(LocalDate.of(9999, 12, 31));
                        });

        assertThat(rules)
                .filteredOn(rule -> TaxRuleType.TAX_RATE == rule.getRuleType())
                .singleElement()
                .satisfies(rule -> assertThat(rule.getRuleValue()).isEqualByComparingTo("0.22"));
    }

    @Test
    @DisplayName("규칙의 모든 컬럼이 DTO 필드로 매핑된다")
    void findTaxRules_컬럼매핑() {
        fixture.insertSeedTaxRules();

        TaxRuleDTO rule =
                taxMapper.findTaxRules().stream()
                        .filter(
                                r ->
                                        TaxRuleType.RELIEF_RATE == r.getRuleType()
                                                && r.getValidFrom()
                                                        .equals(LocalDate.of(2026, 1, 1)))
                        .findFirst()
                        .orElseThrow();

        assertThat(rule.getRuleId()).isNotNull();
        assertThat(rule.getRuleType()).isEqualTo(TaxRuleType.RELIEF_RATE);
        assertThat(rule.getRuleValue()).isEqualByComparingTo("100");
        assertThat(rule.getValidFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(rule.getValidTo()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("확정산된 매도 lot의 모든 컬럼이 DTO 필드로 매핑된다")
    void findLots_컬럼매핑() {
        Long lotId = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderId =
                fixture.insertSellOrder(
                        lotId, "EXECUTED", LocalDateTime.of(2026, 3, 10, 10, 0), "100.0000");
        fixture.insertKrwExchange(
                ACCOUNT_ID,
                orderId,
                "FINALIZED",
                "24000000.00",
                LocalDateTime.of(2026, 3, 10, 10, 0));

        List<SellLotDTO> lots =
                taxMapper.findFinalizedLotsByAccountIdsAndYear(
                        List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE);

        assertThat(lots)
                .singleElement()
                .satisfies(
                        lot -> {
                            assertThat(lot.getOrderId()).isEqualTo(orderId);
                            assertThat(lot.getInboundDetailId()).isEqualTo(lotId);
                            assertThat(lot.getSellAt()).isEqualTo(LocalDate.of(2026, 3, 10));
                            assertThat(lot.getFinalAmount()).isEqualByComparingTo("24000000");
                            assertThat(lot.getSellQty()).isEqualByComparingTo("100");
                            assertThat(lot.getPurchasePrice()).isEqualByComparingTo("150");
                            assertThat(lot.getPurchaseFxRate()).isEqualByComparingTo("1300");
                        });
    }

    @Test
    @DisplayName("가정산(PROVISIONAL) 건은 조회되지 않는다")
    void findLots_가정산_제외() {
        Long lotId = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderId =
                fixture.insertSellOrder(
                        lotId, "EXECUTED", LocalDateTime.of(2026, 3, 10, 10, 0), "100.0000");
        fixture.insertKrwExchange(ACCOUNT_ID, orderId, "PROVISIONAL", "23760000.00", null);

        assertThat(
                        taxMapper.findFinalizedLotsByAccountIdsAndYear(
                                List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("EXECUTED가 아닌 매도 주문은 조회되지 않는다")
    void findLots_미체결_제외() {
        Long lotId = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderId =
                fixture.insertSellOrder(
                        lotId, "RECEIVED", LocalDateTime.of(2026, 3, 10, 10, 0), "100.0000");
        fixture.insertKrwExchange(
                ACCOUNT_ID,
                orderId,
                "FINALIZED",
                "24000000.00",
                LocalDateTime.of(2026, 3, 10, 10, 0));

        assertThat(
                        taxMapper.findFinalizedLotsByAccountIdsAndYear(
                                List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("다른 계좌의 매도는 조회되지 않는다")
    void findLots_타계좌_제외() {
        Long lotId = fixture.insertLot(OTHER_ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderId =
                fixture.insertSellOrder(
                        lotId, "EXECUTED", LocalDateTime.of(2026, 3, 10, 10, 0), "100.0000");
        fixture.insertKrwExchange(
                OTHER_ACCOUNT_ID,
                orderId,
                "FINALIZED",
                "24000000.00",
                LocalDateTime.of(2026, 3, 10, 10, 0));

        assertThat(
                        taxMapper.findFinalizedLotsByAccountIdsAndYear(
                                List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("과세연도가 다른 매도는 조회되지 않는다")
    void findLots_타연도_제외() {
        Long lotId = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderId =
                fixture.insertSellOrder(
                        lotId, "EXECUTED", LocalDateTime.of(2025, 12, 31, 10, 0), "100.0000");
        fixture.insertKrwExchange(
                ACCOUNT_ID,
                orderId,
                "FINALIZED",
                "24000000.00",
                LocalDateTime.of(2025, 12, 31, 10, 0));

        assertThat(
                        taxMapper.findFinalizedLotsByAccountIdsAndYear(
                                List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("업무 기준시각 이후의 매도는 조회되지 않는다")
    void findLots_기준시각_이후_제외() {
        Long lotId = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderId =
                fixture.insertSellOrder(
                        lotId, "EXECUTED", LocalDateTime.of(2026, 9, 20, 10, 0), "100.0000");
        fixture.insertKrwExchange(
                ACCOUNT_ID,
                orderId,
                "FINALIZED",
                "24000000.00",
                LocalDateTime.of(2026, 9, 20, 10, 0));

        LocalDateTime before = LocalDateTime.of(2026, 6, 30, 0, 0);

        assertThat(
                        taxMapper.findFinalizedLotsByAccountIdsAndYear(
                                List.of(ACCOUNT_ID), TAX_YEAR, before))
                .isEmpty();
        assertThat(
                        taxMapper.findFinalizedLotsByAccountIdsAndYear(
                                List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE))
                .hasSize(1);
    }

    @Test
    @DisplayName("한 lot을 여러 번 나눠 판 경우 매도 건마다 각각 조회된다")
    void findLots_동일lot_다건매도() {
        Long lotId = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long first =
                fixture.insertSellOrder(
                        lotId, "EXECUTED", LocalDateTime.of(2026, 3, 10, 10, 0), "40.0000");
        Long second =
                fixture.insertSellOrder(
                        lotId, "EXECUTED", LocalDateTime.of(2026, 9, 20, 10, 0), "60.0000");
        fixture.insertKrwExchange(
                ACCOUNT_ID, first, "FINALIZED", "10000000.00", LocalDateTime.of(2026, 3, 10, 10, 0));
        fixture.insertKrwExchange(
                ACCOUNT_ID,
                second,
                "FINALIZED",
                "15000000.00",
                LocalDateTime.of(2026, 9, 20, 10, 0));

        List<SellLotDTO> lots =
                taxMapper.findFinalizedLotsByAccountIdsAndYear(
                        List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE);

        assertThat(lots).hasSize(2);
        assertThat(lots).extracting(SellLotDTO::getInboundDetailId).containsOnly(lotId);
        assertThat(lots)
                .extracting(SellLotDTO::getSellQty)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("40"), new BigDecimal("60"));
    }

    @Test
    @DisplayName("매도 이력이 없으면 빈 목록을 반환한다")
    void findLots_없으면_빈목록() {
        assertThat(
                        taxMapper.findFinalizedLotsByAccountIdsAndYear(
                                List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("여러 계좌를 IN절 하나로 조회해도 계좌별 lot이 섞이지 않는다")
    void findLots_여러계좌_한번에_조회해도_섞이지_않는다() {
        Long lotA = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderA =
                fixture.insertSellOrder(
                        lotA, "EXECUTED", LocalDateTime.of(2026, 3, 10, 10, 0), "100.0000");
        fixture.insertKrwExchange(
                ACCOUNT_ID, orderA, "FINALIZED", "24000000.00", LocalDateTime.of(2026, 3, 10, 10, 0));

        Long lotB = fixture.insertLot(OTHER_ACCOUNT_ID, "200.0000", "1300.0000", "50.0000");
        Long orderB =
                fixture.insertSellOrder(
                        lotB, "EXECUTED", LocalDateTime.of(2026, 6, 15, 10, 0), "50.0000");
        fixture.insertKrwExchange(
                OTHER_ACCOUNT_ID,
                orderB,
                "FINALIZED",
                "13000000.00",
                LocalDateTime.of(2026, 6, 15, 10, 0));

        List<SellLotDTO> lots =
                taxMapper.findFinalizedLotsByAccountIdsAndYear(
                        List.of(ACCOUNT_ID, OTHER_ACCOUNT_ID), TAX_YEAR, CALC_BASE);

        assertThat(lots).hasSize(2);
        assertThat(lots)
                .filteredOn(lot -> lot.getAccountId().equals(ACCOUNT_ID))
                .singleElement()
                .satisfies(lot -> assertThat(lot.getInboundDetailId()).isEqualTo(lotA));
        assertThat(lots)
                .filteredOn(lot -> lot.getAccountId().equals(OTHER_ACCOUNT_ID))
                .singleElement()
                .satisfies(lot -> assertThat(lot.getInboundDetailId()).isEqualTo(lotB));
    }

    @Test
    @DisplayName("IN절에 넣지 않은 계좌의 lot은 결과에 섞이지 않는다")
    void findLots_IN절에_없는_계좌는_결과에서_빠진다() {
        Long lotA = fixture.insertLot(ACCOUNT_ID, "150.0000", "1300.0000", "100.0000");
        Long orderA =
                fixture.insertSellOrder(
                        lotA, "EXECUTED", LocalDateTime.of(2026, 3, 10, 10, 0), "100.0000");
        fixture.insertKrwExchange(
                ACCOUNT_ID, orderA, "FINALIZED", "24000000.00", LocalDateTime.of(2026, 3, 10, 10, 0));

        Long lotB = fixture.insertLot(OTHER_ACCOUNT_ID, "200.0000", "1300.0000", "50.0000");
        Long orderB =
                fixture.insertSellOrder(
                        lotB, "EXECUTED", LocalDateTime.of(2026, 6, 15, 10, 0), "50.0000");
        fixture.insertKrwExchange(
                OTHER_ACCOUNT_ID,
                orderB,
                "FINALIZED",
                "13000000.00",
                LocalDateTime.of(2026, 6, 15, 10, 0));

        List<SellLotDTO> lots =
                taxMapper.findFinalizedLotsByAccountIdsAndYear(
                        List.of(ACCOUNT_ID), TAX_YEAR, CALC_BASE);

        assertThat(lots)
                .singleElement()
                .satisfies(lot -> assertThat(lot.getAccountId()).isEqualTo(ACCOUNT_ID));
    }

    private static final String CI_HASH = "a".repeat(64);
    private static final String OTHER_CI_HASH = "b".repeat(64);

    @Test
    @DisplayName("판정건의 컬럼이 DTO 필드로 매핑된다")
    void findExternal_컬럼매핑() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, true, "2026-06-15", "20000000.00");

        List<ExternalBuyDTO> result =
                taxMapper.findExternalBuysByAccountIdsAndYear(
                        List.of(accountId), TAX_YEAR, CALC_BASE);

        assertThat(result)
                .singleElement()
                .satisfies(
                        external -> {
                            assertThat(external.getTradeDate())
                                    .isEqualTo(LocalDate.of(2026, 6, 15));
                            assertThat(external.getNetBuyAmount()).isEqualByComparingTo("20000000");
                        });
    }

    @Test
    @DisplayName("SELL 판정건은 net_buy_amount가 음수 그대로 조회된다")
    void findExternal_매도는_음수() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, true, "2026-09-20", "-10000000.00");

        List<ExternalBuyDTO> result =
                taxMapper.findExternalBuysByAccountIdsAndYear(
                        List.of(accountId), TAX_YEAR, CALC_BASE);

        assertThat(result)
                .singleElement()
                .satisfies(
                        external ->
                                assertThat(external.getNetBuyAmount())
                                        .isEqualByComparingTo("-10000000"));
    }

    @Test
    @DisplayName("다른 고객(ci_hash)의 판정건은 조회되지 않는다")
    void findExternal_타고객_제외() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);
        Long otherAccountId = fixture.insertCustomerWithAccount(OTHER_CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, true, "2026-06-15", "20000000.00");
        fixture.insertJudgement(2L, OTHER_CI_HASH, true, "2026-06-15", "70000000.00");

        assertThat(
                        taxMapper.findExternalBuysByAccountIdsAndYear(
                                List.of(accountId), TAX_YEAR, CALC_BASE))
                .extracting(ExternalBuyDTO::getNetBuyAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("20000000"));

        assertThat(
                        taxMapper.findExternalBuysByAccountIdsAndYear(
                                List.of(otherAccountId), TAX_YEAR, CALC_BASE))
                .extracting(ExternalBuyDTO::getNetBuyAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("70000000"));
    }

    @Test
    @DisplayName("존재하지 않는 계좌로 조회하면 빈 목록이다")
    void findExternal_없는계좌() {
        fixture.insertCustomerWithAccount(CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, true, "2026-06-15", "20000000.00");

        assertThat(
                        taxMapper.findExternalBuysByAccountIdsAndYear(
                                List.of(999L), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("계좌가 없는 고객의 판정건은 조회되지 않는다")
    void findExternal_계좌미개설고객_제외() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);
        fixture.insertCustomerOnly(OTHER_CI_HASH);
        fixture.insertJudgement(1L, OTHER_CI_HASH, true, "2026-06-15", "70000000.00");

        assertThat(
                        taxMapper.findExternalBuysByAccountIdsAndYear(
                                List.of(accountId), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("is_target=false인 판정건은 조회되지 않는다")
    void findExternal_비대상_제외() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, false, "2026-06-15", "20000000.00");

        assertThat(
                        taxMapper.findExternalBuysByAccountIdsAndYear(
                                List.of(accountId), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    @Test
    @DisplayName("과세연도가 다른 판정건은 조회되지 않는다")
    void findExternal_타연도_제외() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, true, "2025-12-31", "20000000.00");
        fixture.insertJudgement(2L, CI_HASH, true, "2027-01-01", "30000000.00");
        fixture.insertJudgement(3L, CI_HASH, true, "2026-01-01", "10000000.00");

        assertThat(
                        taxMapper.findExternalBuysByAccountIdsAndYear(
                                List.of(accountId), TAX_YEAR, CALC_BASE))
                .extracting(ExternalBuyDTO::getNetBuyAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10000000"));
    }

    @Test
    @DisplayName("여러 판정건이 있으면 합산 없이 건별로 조회된다(가중치는 계산기가 건별로 적용)")
    void findExternal_건별조회() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, true, "2026-03-10", "10000000.00");
        fixture.insertJudgement(2L, CI_HASH, true, "2026-06-15", "20000000.00");
        fixture.insertJudgement(3L, CI_HASH, true, "2026-09-20", "-10000000.00");

        List<ExternalBuyDTO> result =
                taxMapper.findExternalBuysByAccountIdsAndYear(
                        List.of(accountId), TAX_YEAR, CALC_BASE);

        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(ExternalBuyDTO::getTradeDate)
                .containsExactlyInAnyOrder(
                        LocalDate.of(2026, 3, 10),
                        LocalDate.of(2026, 6, 15),
                        LocalDate.of(2026, 9, 20));
    }

    @Test
    @DisplayName("여러 계좌를 IN절 하나로 조회해도 계좌별 판정건이 섞이지 않는다")
    void findExternal_여러계좌_한번에_조회해도_섞이지_않는다() {
        Long accountA = fixture.insertCustomerWithAccount(CI_HASH);
        Long accountB = fixture.insertCustomerWithAccount(OTHER_CI_HASH);
        fixture.insertJudgement(1L, CI_HASH, true, "2026-06-15", "20000000.00");
        fixture.insertJudgement(2L, OTHER_CI_HASH, true, "2026-09-20", "-10000000.00");

        List<ExternalBuyDTO> result =
                taxMapper.findExternalBuysByAccountIdsAndYear(
                        List.of(accountA, accountB), TAX_YEAR, CALC_BASE);

        assertThat(result).hasSize(2);
        assertThat(result)
                .filteredOn(external -> external.getAccountId().equals(accountA))
                .singleElement()
                .satisfies(
                        external ->
                                assertThat(external.getNetBuyAmount())
                                        .isEqualByComparingTo("20000000"));
        assertThat(result)
                .filteredOn(external -> external.getAccountId().equals(accountB))
                .singleElement()
                .satisfies(
                        external ->
                                assertThat(external.getNetBuyAmount())
                                        .isEqualByComparingTo("-10000000"));
    }

    @Test
    @DisplayName("판정건이 없으면 빈 목록을 반환한다")
    void findExternal_없으면_빈목록() {
        Long accountId = fixture.insertCustomerWithAccount(CI_HASH);

        assertThat(
                        taxMapper.findExternalBuysByAccountIdsAndYear(
                                List.of(accountId), TAX_YEAR, CALC_BASE))
                .isEmpty();
    }

    private TaxCalculationDTO calculation(Long accountId, TaxBasisType basisType) {
        return TaxCalculationDTO.builder()
                .accountId(accountId)
                .calculatedAt(LocalDateTime.of(2027, 5, 1, 9, 0))
                .basisType(basisType)
                .weightedSell(new BigDecimal("43000000.00"))
                .originalGainAmount(new BigDecimal("32000000.00"))
                .weightedGain(new BigDecimal("27800000.00"))
                .weightedExternalAmount(new BigDecimal("11000000.00"))
                .adjustRatio(new BigDecimal("0.7442"))
                .finalDeduction(new BigDecimal("20688760.00"))
                .finalTax(new BigDecimal("1938472.80"))
                .build();
    }

    @Test
    @DisplayName("저장하면 생성된 calc_id가 DTO에 채워진다")
    void insertCalculation_생성키() {
        TaxCalculationDTO dto = calculation(ACCOUNT_ID, TaxBasisType.FINAL_REPORT);

        taxMapper.insertCalculation(dto);

        assertThat(dto.getCalcId()).isNotNull();
    }

    @Test
    @DisplayName("저장한 값이 모든 컬럼에 그대로 들어간다")
    void insertCalculation_컬럼매핑() {
        TaxCalculationDTO dto = calculation(ACCOUNT_ID, TaxBasisType.FINAL_REPORT);
        taxMapper.insertCalculation(dto);

        assertThat(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .isTrue();
        assertThat(
                        selectOne(
                                "select basis_type from tax_calculation where calc_id = "
                                        + dto.getCalcId()))
                .isEqualTo("FINAL_REPORT");
        assertThat(
                        selectOne(
                                "select adjust_ratio from tax_calculation where calc_id = "
                                        + dto.getCalcId()))
                .isEqualTo("0.7442");
        assertThat(
                        selectOne(
                                "select final_tax from tax_calculation where calc_id = "
                                        + dto.getCalcId()))
                .isEqualTo("1938472.80");
    }

    @Test
    @DisplayName("같은 계좌라도 basis_type이 다르면 각각 저장된다")
    void insertCalculation_두_유형() {
        taxMapper.insertCalculation(calculation(ACCOUNT_ID, TaxBasisType.FINAL_REPORT));
        taxMapper.insertCalculation(
                calculation(ACCOUNT_ID, TaxBasisType.EARLY_WITHDRAWAL_CLAWBACK));

        assertThat(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .isTrue();
        assertThat(
                        taxMapper.existsByAccountAndBasis(
                                ACCOUNT_ID, TaxBasisType.EARLY_WITHDRAWAL_CLAWBACK))
                .isTrue();
    }

    @Test
    @DisplayName("같은 계좌·같은 basis_type은 UNIQUE 제약으로 막힌다")
    void insertCalculation_중복차단() {
        taxMapper.insertCalculation(calculation(ACCOUNT_ID, TaxBasisType.FINAL_REPORT));

        assertThatThrownBy(
                        () ->
                                taxMapper.insertCalculation(
                                        calculation(ACCOUNT_ID, TaxBasisType.FINAL_REPORT)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("다른 계좌의 계산은 exists 판정에 섞이지 않는다")
    void existsByAccountAndBasis_타계좌_제외() {
        taxMapper.insertCalculation(calculation(OTHER_ACCOUNT_ID, TaxBasisType.FINAL_REPORT));

        assertThat(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .isFalse();
        assertThat(taxMapper.existsByAccountAndBasis(OTHER_ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .isTrue();
    }

    @Test
    @DisplayName("저장된 계산이 없으면 exists는 false")
    void existsByAccountAndBasis_없음() {
        assertThat(taxMapper.existsByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .isFalse();
    }

    private String selectOne(String sql) {
        try (java.sql.Connection c = dataSource.getConnection();
                java.sql.Statement st = c.createStatement();
                java.sql.ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
