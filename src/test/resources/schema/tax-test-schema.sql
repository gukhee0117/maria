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

CREATE TABLE tax_rule (
    rule_id    BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_type  VARCHAR(20)   NOT NULL,
    rule_value DECIMAL(15,4) NOT NULL,
    valid_from DATE          NOT NULL,
    valid_to   DATE          NOT NULL,
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(30)
);

CREATE TABLE inbound (
    inbound_id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id                 BIGINT        NOT NULL,
    requested_qty              DECIMAL(15,4) NOT NULL,
    current_holding_at_request DECIMAL(15,4),
    approved_qty               DECIMAL(15,4) NOT NULL,
    processed_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inbound_detail (
    inbound_detail_id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    inbound_id                BIGINT        NOT NULL,
    foreign_product_id        BIGINT        NOT NULL,
    source_broker             VARCHAR(20),
    source_general_account_id BIGINT,
    recorded_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    purchase_date             DATETIME      NOT NULL,
    purchase_price            DECIMAL(15,4) NOT NULL,
    purchase_currency         VARCHAR(10)   NOT NULL,
    purchase_fx_rate          DECIMAL(15,4) NOT NULL,
    qty                       DECIMAL(15,4) NOT NULL,
    current_qty               DECIMAL(15,4) NOT NULL
);

CREATE TABLE sell_order (
    order_id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    inbound_detail_id  BIGINT,
    sell_qty           DECIMAL(15,4) NOT NULL,
    base_price         DECIMAL(15,4) NOT NULL,
    processed_at       DATETIME,
    status             VARCHAR(10)   NOT NULL,
    settlement_fx_rate DECIMAL(15,4)
);

CREATE TABLE krw_exchange (
    exchange_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id         BIGINT        NOT NULL,
    order_id           BIGINT        NOT NULL,
    provisional_amount DECIMAL(15,2) NOT NULL,
    provisional_at     DATETIME      NOT NULL,
    final_rate         DECIMAL(15,6),
    final_amount       DECIMAL(15,0),
    final_at           DATETIME,
    settlement_status  VARCHAR(12)   NOT NULL
);

CREATE TABLE customer (
    customer_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL,
    birth_date    DATE         NOT NULL,
    phone         VARCHAR(20),
    investor_type VARCHAR(20)  NOT NULL,
    ci_hash       VARCHAR(64)  NOT NULL UNIQUE,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE account (
    account_id   BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id  BIGINT        NOT NULL UNIQUE,
    status       VARCHAR(20)   NOT NULL,
    opened_at    DATETIME,
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_no   VARCHAR(10),
    limit_amount DECIMAL(15,0) NOT NULL,
    amount       DECIMAL(15,0) NOT NULL DEFAULT 0,
    benefit      VARCHAR(12)
);

-- account_id와 customer_id가 같은 값이면 조인 조건이 틀려도 테스트가 통과한다
ALTER TABLE account ALTER COLUMN account_id RESTART WITH 1000;

CREATE TABLE target_product_judgement (
    judgement_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    mydata_trade_id     BIGINT        NOT NULL UNIQUE,
    ci_hash             VARCHAR(64)   NOT NULL,
    fund_code           VARCHAR(12),
    fund_name           VARCHAR(100),
    ticker              VARCHAR(20),
    is_target           BOOLEAN       NOT NULL,
    foreign_stock_ratio DECIMAL(5,2),
    inception_date      DATE,
    judged_at           DATETIME      NOT NULL,
    trade_type          VARCHAR(15)   NOT NULL,
    amount              DECIMAL(15,2) NOT NULL,
    trade_date          DATE          NOT NULL,
    net_buy_amount      DECIMAL(15,2) NOT NULL
);

CREATE TABLE tax_calculation (
    calc_id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id               BIGINT        NOT NULL,
    calculated_at            DATETIME      NOT NULL,
    basis_type               VARCHAR(30)   NOT NULL,
    weighted_sell            DECIMAL(15,2) NOT NULL,
    original_gain_amount     DECIMAL(15,2) NOT NULL,
    weighted_gain            DECIMAL(15,2) NOT NULL,
    weighted_external_amount DECIMAL(15,2) NOT NULL,
    adjust_ratio             DECIMAL(7,4)  NOT NULL,
    final_deduction          DECIMAL(15,2) NOT NULL,
    final_tax                DECIMAL(15,2) NOT NULL,
    CONSTRAINT uk_tax_calc__account_basis UNIQUE (account_id, basis_type)
);

CREATE TABLE tax_snapshot (
    snapshot_id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id               BIGINT        NOT NULL,
    calculated_at            DATETIME      NOT NULL,
    weighted_sell            DECIMAL(15,2) NOT NULL,
    original_gain_amount     DECIMAL(15,2) NOT NULL,
    weighted_gain            DECIMAL(15,2) NOT NULL,
    weighted_external_amount DECIMAL(15,2) NOT NULL,
    adjust_ratio             DECIMAL(7,4)  NOT NULL,
    final_deduction          DECIMAL(15,2) NOT NULL,
    final_tax                DECIMAL(15,2) NOT NULL,
    CONSTRAINT uk_tax_snapshot__account UNIQUE (account_id)
);
