package com.app.maria.domain.tax.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import javax.sql.DataSource;

public class TaxTestFixture {

    private static final String SCHEMA_PATH = "schema/tax-test-schema.sql";

    private final DataSource dataSource;

    public TaxTestFixture(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            for (String ddl : readSchema().split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
        }
    }

    public void insertSeedTaxRules() {
        execute(
                """
                INSERT INTO tax_rule (rule_type, rule_value, valid_from, valid_to) VALUES
                ('DEPOSIT_LIMIT',   50000000.0000, '2026-01-01', '9999-12-31'),
                ('HOLDING_PERIOD',         1.0000, '2026-01-01', '9999-12-31'),
                ('RELIEF_RATE',          100.0000, '2026-01-01', '2026-05-31'),
                ('RELIEF_RATE',           80.0000, '2026-06-01', '2026-07-31'),
                ('RELIEF_RATE',           50.0000, '2026-08-01', '2026-12-31'),
                ('BASIC_DEDUCTION',  2500000.0000, '2026-01-01', '9999-12-31'),
                ('TAX_RATE',               0.2200, '2026-01-01', '9999-12-31')
                """);
    }

    public Long insertLot(Long accountId, String purchasePrice, String purchaseFxRate, String qty) {
        Long inboundId =
                insertReturningId(
                        """
                INSERT INTO inbound (account_id, requested_qty, approved_qty)
                VALUES (%d, %s, %s)
                """
                                .formatted(accountId, qty, qty));

        return insertReturningId(
                """
                INSERT INTO inbound_detail
                    (inbound_id, foreign_product_id, purchase_date, purchase_price,
                     purchase_currency, purchase_fx_rate, qty, current_qty)
                VALUES (%d, 1, '2024-03-10 00:00:00', %s, 'USD', %s, %s, %s)
                """
                        .formatted(inboundId, purchasePrice, purchaseFxRate, qty, qty));
    }

    public Long insertSellOrder(
            Long inboundDetailId, String status, LocalDateTime processedAt, String sellQty) {
        return insertReturningId(
                """
                INSERT INTO sell_order (inbound_detail_id, sell_qty, base_price, status, processed_at)
                VALUES (%d, %s, 200000.0000, '%s', '%s')
                """
                        .formatted(inboundDetailId, sellQty, status, processedAt));
    }

    public void insertKrwExchange(
            Long accountId,
            Long orderId,
            String settlementStatus,
            String finalAmount,
            LocalDateTime finalAt) {
        execute(
                """
                INSERT INTO krw_exchange
                    (account_id, order_id, provisional_amount, provisional_at, final_amount, final_at, settlement_status)
                VALUES (%d, %d, %s, '2026-03-10 10:00:00', %s, %s, '%s')
                """
                        .formatted(
                                accountId,
                                orderId,
                                finalAmount,
                                finalAmount,
                                finalAt == null ? "NULL" : "'" + finalAt + "'",
                                settlementStatus));
    }

    public Long insertCustomerWithAccount(String ciHash) {
        Long customerId =
                insertReturningId(
                        """
                INSERT INTO customer (name, birth_date, investor_type, ci_hash)
                VALUES ('테스트고객', '1990-01-01', 'NEUTRAL', '%s')
                """
                                .formatted(ciHash));

        return insertReturningId(
                """
                INSERT INTO account (customer_id, status, limit_amount, amount, benefit)
                VALUES (%d, 'OPENED', 50000000, 0, 'POSSIBLE')
                """
                        .formatted(customerId));
    }

    public void insertCustomerOnly(String ciHash) {
        execute(
                """
                INSERT INTO customer (name, birth_date, investor_type, ci_hash)
                VALUES ('계좌없는고객', '1990-01-01', 'NEUTRAL', '%s')
                """
                        .formatted(ciHash));
    }

    public void insertJudgement(
            Long mydataTradeId,
            String ciHash,
            boolean isTarget,
            String tradeDate,
            String netBuyAmount) {
        execute(
                """
                INSERT INTO target_product_judgement
                    (mydata_trade_id, ci_hash, is_target, judged_at, trade_type, amount, trade_date, net_buy_amount)
                VALUES (%d, '%s', %b, '2026-12-31 01:00:00', '%s', %s, '%s', %s)
                """
                        .formatted(
                                mydataTradeId,
                                ciHash,
                                isTarget,
                                netBuyAmount.startsWith("-") ? "SELL" : "BUY",
                                netBuyAmount.replace("-", ""),
                                tradeDate,
                                netBuyAmount));
    }

    private String readSchema() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SCHEMA_PATH)) {
            if (in == null) {
                throw new IllegalStateException("테스트 스키마를 찾을 수 없습니다: " + SCHEMA_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Long insertReturningId(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
