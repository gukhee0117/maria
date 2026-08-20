DROP TABLE IF EXISTS tax_snapshot;
DROP TABLE IF EXISTS tax_rule;
DROP TABLE IF EXISTS target_product_judgement;
DROP TABLE IF EXISTS krw_exchange;
DROP TABLE IF EXISTS sell_order;
DROP TABLE IF EXISTS inbound_detail;
DROP TABLE IF EXISTS account_benefit_log;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS customer;
DROP TABLE IF EXISTS foreign_product;

CREATE TABLE foreign_product (
    foreign_product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticker              VARCHAR(20)  NOT NULL,
    name                VARCHAR(100) NOT NULL,
    market              VARCHAR(20),
    currency            VARCHAR(10),
    type                VARCHAR(20)
);

INSERT INTO foreign_product (foreign_product_id, ticker, name, market, currency, type)
VALUES (1, 'AAPL', 'Apple Inc.', 'NASDAQ', 'USD', 'FOREIGN_STOCK');

CREATE TABLE customer (
    customer_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL,
    birth_date    DATE         NOT NULL,
    investor_type VARCHAR(20)  NOT NULL,
    ci_hash       VARCHAR(64)  NOT NULL UNIQUE
);

CREATE TABLE account (
    account_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id  BIGINT        NOT NULL UNIQUE,
    status       VARCHAR(20)   NOT NULL,
    opened_at    DATETIME,
    limit_amount DECIMAL(15, 0) NOT NULL DEFAULT 30000000,
    amount       DECIMAL(15, 0) NOT NULL DEFAULT 0,
    benefit      VARCHAR(12)
);

CREATE TABLE account_benefit_log (
    benefit_id  BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id  BIGINT       NOT NULL,
    prev_status VARCHAR(12),
    new_status  VARCHAR(12)  NOT NULL,
    changed_at  DATETIME     NOT NULL,
    reason      VARCHAR(200)
);

CREATE TABLE inbound_detail (
    inbound_detail_id  BIGINT PRIMARY KEY AUTO_INCREMENT,
    foreign_product_id BIGINT        NOT NULL DEFAULT 1,
    purchase_price     DECIMAL(15, 4) NOT NULL,
    purchase_fx_rate   DECIMAL(15, 4) NOT NULL
);

CREATE TABLE sell_order (
    order_id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    inbound_detail_id BIGINT        NOT NULL,
    sell_qty          DECIMAL(15, 4) NOT NULL,
    base_price        DECIMAL(15, 4) NOT NULL,
    status            VARCHAR(10)   NOT NULL,
    processed_at      DATETIME      NOT NULL
);

CREATE TABLE krw_exchange (
    exchange_id       BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id        BIGINT        NOT NULL,
    order_id          BIGINT        NOT NULL,
    final_amount      DECIMAL(15, 0),
    final_at          DATETIME,
    settlement_status VARCHAR(12)   NOT NULL
);

CREATE TABLE target_product_judgement (
    judgement_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
    mydata_trade_id BIGINT        NOT NULL UNIQUE,
    ci_hash         VARCHAR(64)   NOT NULL,
    fund_name       VARCHAR(100),
    ticker          VARCHAR(20),
    is_target       BOOLEAN       NOT NULL,
    judged_at       DATETIME      NOT NULL,
    trade_date      DATE          NOT NULL,
    net_buy_amount  DECIMAL(15, 2) NOT NULL
);

CREATE TABLE tax_rule (
    rule_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_type  VARCHAR(20)   NOT NULL,
    rule_value DECIMAL(15, 4) NOT NULL,
    valid_from DATE          NOT NULL,
    valid_to   DATE          NOT NULL
);

CREATE TABLE tax_snapshot (
    snapshot_id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id               BIGINT        NOT NULL,
    calculated_at            DATETIME      NOT NULL,
    weighted_sell            DECIMAL(15, 2) NOT NULL,
    original_gain_amount     DECIMAL(15, 2) NOT NULL,
    weighted_gain            DECIMAL(15, 2) NOT NULL,
    weighted_external_amount DECIMAL(15, 2) NOT NULL,
    adjust_ratio             DECIMAL(7, 4) NOT NULL,
    final_deduction          DECIMAL(15, 2) NOT NULL,
    final_tax                DECIMAL(15, 2) NOT NULL,
    CONSTRAINT uk_tax_snapshot__account UNIQUE (account_id)
);
