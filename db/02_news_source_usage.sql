-- Apply once to an existing MariaDB database before deploying the persistent news API quota counter.
CREATE TABLE IF NOT EXISTS news_source_monthly_usage (
    usage_month   CHAR(7)      NOT NULL COMMENT 'UTC 기준 API 사용 월(YYYY-MM)',
    request_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '해당 월 뉴스 API 요청 예약 수',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (usage_month),
    CONSTRAINT chk_nsmu_month CHECK (usage_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='뉴스 공급자 월간 API 요청 한도 추적';
