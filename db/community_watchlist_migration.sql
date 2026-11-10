-- 기존 데이터 유지: 관심종목 테이블만 추가. 반복 실행 가능.
USE ria_admin;
CREATE TABLE IF NOT EXISTS community_watchlist (
    customer_id BIGINT NOT NULL,
    ticker VARCHAR(20) NOT NULL COMMENT '국내·해외 종목 마스터 코드',
    created_at DATETIME NOT NULL COMMENT 'SYSTEM_CLOCK 기준 등록시각',
    PRIMARY KEY (customer_id, ticker),
    KEY ix_cw_customer_created (customer_id, created_at, ticker),
    CONSTRAINT fk_cw_customer FOREIGN KEY (customer_id) REFERENCES customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='고객별 커뮤니티 관심종목';
