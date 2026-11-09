DROP TABLE IF EXISTS risk_grade_sync_state;
DROP TABLE IF EXISTS stock_risk_grade;
DROP TABLE IF EXISTS domestic_product;
CREATE TABLE domestic_product (
    domestic_product_id BIGINT NOT NULL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL DEFAULT '',
    type VARCHAR(10) NOT NULL DEFAULT 'STOCK'
) ENGINE=InnoDB;
CREATE TABLE stock_risk_grade (
    domestic_product_id BIGINT NOT NULL PRIMARY KEY,
    var_975 DECIMAL(7,4) NOT NULL,
    risk_grade TINYINT NOT NULL,
    volatility DECIMAL(7,4) NOT NULL,
    price_from DATE NOT NULL,
    price_to DATE NOT NULL,
    calculated_at DATETIME NOT NULL,
    synced_at DATETIME NOT NULL,
    analysis_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_test_risk_product FOREIGN KEY(domestic_product_id) REFERENCES domestic_product(domestic_product_id),
    CONSTRAINT chk_test_analysis_version CHECK(analysis_version >= 0),
    CONSTRAINT chk_srg_grade CHECK(risk_grade BETWEEN 1 AND 6)
) ENGINE=InnoDB;
CREATE TABLE risk_grade_sync_state (
    state_id TINYINT NOT NULL PRIMARY KEY,
    next_poll_at DATETIME NULL,
    next_retry_at DATETIME NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME NULL,
    last_success_at DATETIME NULL,
    last_error_code VARCHAR(80) NULL,
    last_run_kind VARCHAR(10) NULL,
    CONSTRAINT chk_test_rgss_singleton CHECK(state_id = 1),
    CONSTRAINT chk_test_rgss_retry CHECK(retry_count >= 0)
) ENGINE=InnoDB;
INSERT INTO risk_grade_sync_state(state_id) VALUES (1);
INSERT INTO domestic_product (domestic_product_id,ticker) VALUES (1,'005930'),(2,'000660');
