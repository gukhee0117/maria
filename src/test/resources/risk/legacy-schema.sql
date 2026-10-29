DROP TABLE IF EXISTS risk_grade_sync_state;
DROP TABLE IF EXISTS stock_risk_grade;
DROP TABLE IF EXISTS domestic_product;
CREATE TABLE domestic_product (
    domestic_product_id BIGINT NOT NULL PRIMARY KEY,
    ticker VARCHAR(20) NOT NULL
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
    CONSTRAINT fk_test_risk_product FOREIGN KEY(domestic_product_id) REFERENCES domestic_product(domestic_product_id),
    CONSTRAINT chk_srg_grade CHECK(risk_grade BETWEEN 1 AND 6)
) ENGINE=InnoDB;
INSERT INTO domestic_product VALUES (1,'005930'),(2,'000660');
