-- Manual maintenance migration. Back up and verify on a disposable DB first.
-- Not auto-run by the application. Do not run 01_ria_ddl.sql on an existing DB.
ALTER TABLE stock_risk_grade
    ADD COLUMN IF NOT EXISTS analysis_version BIGINT NOT NULL DEFAULT 0
        COMMENT '0=이관된 미확인 버전, 1 이상=Python 검증 게시 버전',
    ADD CONSTRAINT IF NOT EXISTS chk_srg_analysis_version CHECK (analysis_version >= 0);

CREATE TABLE IF NOT EXISTS risk_grade_sync_state (
    state_id TINYINT NOT NULL PRIMARY KEY,
    next_poll_at DATETIME NULL,
    next_retry_at DATETIME NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME NULL,
    last_success_at DATETIME NULL,
    last_error_code VARCHAR(80) NULL,
    last_run_kind VARCHAR(10) NULL,
    CONSTRAINT chk_rgss_singleton CHECK (state_id = 1),
    CONSTRAINT chk_rgss_retry CHECK (retry_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='위험등급 Pull 주기 및 장애 재시도 상태';
INSERT IGNORE INTO risk_grade_sync_state(state_id) VALUES (1);
