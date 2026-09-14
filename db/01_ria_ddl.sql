-- =====================================================================
-- RIA 관리자 시스템 DDL (MariaDB / MySQL 문법)
-- ERDCloud export를 실행 가능한 DDL로 변환
-- 시스템 경계(§8-8): myData/증권사 참조는 값만 저장, FK 없음
-- 변경: domestic_stock_balance를 증권사 -> RIA로 이동(account/domestic_product FK 복구)
-- =====================================================================

CREATE DATABASE IF NOT EXISTS ria_admin DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE ria_admin;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS admin_user;
DROP TABLE IF EXISTS settlement_batch_guard;
DROP TABLE IF EXISTS settlement_item;
DROP TABLE IF EXISTS settlement_batch;
DROP TABLE IF EXISTS tax_snapshot;
DROP TABLE IF EXISTS tax_calculation;
DROP TABLE IF EXISTS tax_rule;
DROP TABLE IF EXISTS account_closure_request;
DROP TABLE IF EXISTS withdrawal_allocation;
DROP TABLE IF EXISTS withdrawal;
DROP TABLE IF EXISTS left_amount;
DROP TABLE IF EXISTS krw_exchange;
DROP TABLE IF EXISTS sell_order;
DROP TABLE IF EXISTS outbound;
DROP TABLE IF EXISTS target_product_judgement;
DROP TABLE IF EXISTS external_trade_sync_cursor;
DROP TABLE IF EXISTS inbound_min;
DROP TABLE IF EXISTS inbound_detail;
DROP TABLE IF EXISTS inbound;
DROP TABLE IF EXISTS domestic_stock_balance;
DROP TABLE IF EXISTS domestic_product;
DROP TABLE IF EXISTS foreign_product;
DROP TABLE IF EXISTS system_clock;
DROP TABLE IF EXISTS account_benefit_log;
DROP TABLE IF EXISTS account_status_log;
DROP TABLE IF EXISTS account;
DROP TABLE IF EXISTS customer_auth_password;
DROP TABLE IF EXISTS customer_auth;
DROP TABLE IF EXISTS customer;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------
-- 고객 / 인증
-- ---------------------------------------------------------------------
-- [SEED] data-generator가 채우는 테이블. 컬럼 추가/삭제 시 생성기 INSERT(컬럼목록+값)도 수정 후 재생성 필요 (seed.sql 직접 편집 금지).
CREATE TABLE customer (
    customer_id   BIGINT      NOT NULL AUTO_INCREMENT,
    name          VARCHAR(50) NOT NULL COMMENT '이름(표시/감사용, 해시 입력 아님)',
    birth_date    DATE        NOT NULL COMMENT '생년월일',
    phone         VARCHAR(20) NULL     COMMENT '연락처',
    investor_type VARCHAR(20) NOT NULL COMMENT '투자자유형',
    ci_hash       VARCHAR(64) NOT NULL COMMENT 'HMAC-SHA256(정규화 주민번호 13자리, 공유 PEPPER). 원본 미저장. 3개 시스템 공유키(CI 역할)',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일시',
    PRIMARY KEY (customer_id),
    UNIQUE KEY uk_customer_ci_hash (ci_hash),
    CONSTRAINT chk_customer_investor_type CHECK (investor_type IN ('STABLE','CONSERVATIVE','NEUTRAL','ACTIVE','AGGRESSIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RIA 고객';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE customer_auth (
    auth_id       BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id   BIGINT      NOT NULL,
    login_id      VARCHAR(50) NOT NULL COMMENT '로그인ID',
    type          VARCHAR(10) NOT NULL COMMENT 'LOCAL/GOOGLE/KAKAO/NAVER',
    last_login_at DATETIME    NULL     COMMENT '마지막 로그인 일시',
    PRIMARY KEY (auth_id),
    CONSTRAINT chk_customer_auth_type CHECK (type IN ('LOCAL','GOOGLE','KAKAO','NAVER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='고객 인증수단';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE customer_auth_password (
    auth_password_id BIGINT       NOT NULL AUTO_INCREMENT,
    auth_id          BIGINT       NOT NULL,
    password         VARCHAR(255) NOT NULL COMMENT '사용자 비밀번호(해시)',
    PRIMARY KEY (auth_password_id),
    UNIQUE KEY uk_cap_auth_id (auth_id) COMMENT 'type=LOCAL 1:1'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LOCAL 계정 비밀번호(1:1)';

-- ---------------------------------------------------------------------
-- 계좌
-- ---------------------------------------------------------------------
-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE account (
    account_id   BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id  BIGINT        NOT NULL COMMENT '1인 1계좌 - unique',
    status       VARCHAR(20)   NOT NULL COMMENT 'APPLIED/OPENED/REJECTED/CLOSURE_REQUESTED/CLOSED',
    opened_at    DATETIME      NULL     COMMENT '개설일',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '신청일',
    account_no   VARCHAR(10)   NULL     COMMENT '계좌번호(개설 후 부여)',
    limit_amount DECIMAL(15,0) NOT NULL COMMENT '고객 설정 매도한도(전 금융기관 합산 5천만원 이내)',
    amount       DECIMAL(15,0) NOT NULL DEFAULT 0 COMMENT '보유중인 금액(원화)',
    benefit      VARCHAR(12)   NULL     COMMENT '세제혜택: POSSIBLE/IMPOSSIBLE/REDUCED',
    last_domestic_trade_id BIGINT NULL COMMENT '국내거래 pull 커서(마지막 반영한 증권사 domestic_trade.trade_id)',
    PRIMARY KEY (account_id),
    UNIQUE KEY uk_account_customer_id (customer_id),
    UNIQUE KEY uk_account_account_no (account_no),
    CONSTRAINT chk_account_status  CHECK (status IN ('APPLIED','OPENED','REJECTED','CLOSURE_REQUESTED','CLOSED')),
    CONSTRAINT chk_account_benefit CHECK (benefit IN ('POSSIBLE','IMPOSSIBLE','REDUCED')),
    CONSTRAINT chk_account_limit   CHECK (limit_amount > 0 AND limit_amount <= 50000000),
    CONSTRAINT chk_account_no      CHECK (account_no IS NULL OR account_no REGEXP '^[0-9]{10}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RIA 계좌';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE account_status_log (
    log_id      BIGINT       NOT NULL AUTO_INCREMENT,
    account_id  BIGINT       NOT NULL,
    prev_status VARCHAR(20)  NULL     COMMENT '이전상태',
    new_status  VARCHAR(20)  NOT NULL COMMENT '변경상태',
    changed_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '변경일시',
    reason      VARCHAR(200) NULL     COMMENT '변경사유',
    PRIMARY KEY (log_id),
    CONSTRAINT chk_asl_prev CHECK (prev_status IN ('APPLIED','OPENED','REJECTED','CLOSURE_REQUESTED','CLOSED')),
    CONSTRAINT chk_asl_new  CHECK (new_status  IN ('APPLIED','OPENED','REJECTED','CLOSURE_REQUESTED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='계좌 상태 변경 이력';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE account_benefit_log (
    benefit_id  BIGINT       NOT NULL AUTO_INCREMENT,
    account_id  BIGINT       NOT NULL,
    prev_status VARCHAR(12)  NULL     COMMENT '이전 상태',
    new_status  VARCHAR(12)  NOT NULL COMMENT '신규 상태',
    changed_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '변경일시',
    reason      VARCHAR(200) NULL     COMMENT '변경 사유',
    PRIMARY KEY (benefit_id),
    CONSTRAINT chk_abl_prev CHECK (prev_status IN ('POSSIBLE','IMPOSSIBLE','REDUCED')),
    CONSTRAINT chk_abl_new  CHECK (new_status  IN ('POSSIBLE','IMPOSSIBLE','REDUCED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='세제혜택 상태 변경 이력';

-- ---------------------------------------------------------------------
-- 시스템 시계
-- ---------------------------------------------------------------------
-- [SEED] data-generator가 채우는 테이블. 컬럼 추가/삭제 시 생성기 INSERT(컬럼목록+값)도 수정 후 재생성 필요 (seed.sql 직접 편집 금지).
CREATE TABLE system_clock (
    clock_id         BIGINT   NOT NULL ,
    current_datetime DATETIME NOT NULL COMMENT '관리자가 설정한 MARIA 업무 기준시각',
    reference_real_datetime DATETIME NOT NULL
          DEFAULT CURRENT_TIMESTAMP
          COMMENT '업무 기준시각을 설정한 실제 DB 시각',
    PRIMARY KEY (clock_id),
    CONSTRAINT chk_system_clock_singleton
        CHECK (clock_id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MARIA 업무시각 계산 기준값(단일 행)';

CREATE TRIGGER trg_system_clock_prevent_delete
    BEFORE DELETE ON system_clock
    FOR EACH ROW
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'SYSTEM_CLOCK 행은 삭제할 수 없습니다.';

-- ---------------------------------------------------------------------
-- 시세 마스터
-- ---------------------------------------------------------------------
-- [SEED] data-generator가 채우는 테이블. 컬럼 추가/삭제 시 생성기 INSERT(컬럼목록+값)도 수정 후 재생성 필요 (seed.sql 직접 편집 금지).
CREATE TABLE foreign_product (
    foreign_product_id BIGINT       NOT NULL AUTO_INCREMENT,
    ticker             VARCHAR(20)  NOT NULL COMMENT '종목코드',
    name               VARCHAR(100) NOT NULL COMMENT '종목명',
    market             VARCHAR(50)  NOT NULL COMMENT '거래소',
    currency           VARCHAR(10)  NULL     COMMENT '거래통화',
    type               VARCHAR(15)  NOT NULL COMMENT 'FOREIGN_STOCK/ETF/ETN',
    PRIMARY KEY (foreign_product_id),
    CONSTRAINT chk_foreign_product_type CHECK (type IN ('FOREIGN_STOCK','ETF','ETN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='해외 종목 마스터';

-- [SEED] data-generator가 채우는 테이블. 컬럼 추가/삭제 시 생성기 INSERT(컬럼목록+값)도 수정 후 재생성 필요 (seed.sql 직접 편집 금지).
CREATE TABLE domestic_product (
    domestic_product_id BIGINT       NOT NULL AUTO_INCREMENT,
    ticker              VARCHAR(20)  NOT NULL COMMENT '종목코드',
    name                VARCHAR(100) NOT NULL COMMENT '종목명',
    market              VARCHAR(50)  NULL     COMMENT '거래소',
    type                VARCHAR(15)  NOT NULL COMMENT 'STOCK/FUND',
    domestic_stock_ratio DECIMAL(5,2) NULL COMMENT '국내주식 비중%(FUND만). RIA 편입 80% 판정. STOCK은 NULL',
    inception_date       DATE         NULL COMMENT '설정일(FUND만). 1개월 경과 판정. STOCK은 NULL',
    PRIMARY KEY (domestic_product_id),
    CONSTRAINT chk_domestic_product_type CHECK (type IN ('STOCK','FUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='국내 종목 마스터';

-- ---------------------------------------------------------------------
-- RIA 계좌 내 국내주식 잔고 (증권사 -> RIA 이동)
-- ---------------------------------------------------------------------
-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE domestic_stock_balance (
    domestic_stock_balance_id BIGINT        NOT NULL AUTO_INCREMENT,
    account_id                BIGINT        NOT NULL COMMENT 'RIA account (동일 시스템, FK 복구)',
    domestic_product_id       BIGINT        NOT NULL COMMENT '해당 국내 종목',
    qty                       DECIMAL(15,4) NOT NULL COMMENT '보유수량',
    status                    VARCHAR(20)   NOT NULL COMMENT 'HOLDING/PARTIALLY_SOLD/SOLD_OUT/TRADE_RESTRICTED/TRADE_SUSPENDED/TERMINATED',
    last_purchase_date        DATETIME      NOT NULL COMMENT '마지막 매수일',
    avg_purchase_price        DECIMAL(15,4) NOT NULL COMMENT '평균매수단가',
    PRIMARY KEY (domestic_stock_balance_id),
    CONSTRAINT chk_dsb_status CHECK (status IN ('HOLDING','PARTIALLY_SOLD','SOLD_OUT','TRADE_RESTRICTED','TRADE_SUSPENDED','TERMINATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RIA 계좌 국내주식 포지션';

-- ---------------------------------------------------------------------
-- 입고 (3단 분리)
-- ---------------------------------------------------------------------
-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE inbound (
    inbound_id                 BIGINT        NOT NULL AUTO_INCREMENT,
    account_id                 BIGINT        NOT NULL,
    requested_qty              DECIMAL(15,4) NOT NULL COMMENT 'Min 3번 인자: 고객 신청수량',
    current_holding_at_request DECIMAL(15,4) NULL     COMMENT 'Min 2번 인자: 요청시점 보유수량',
    approved_qty               DECIMAL(15,4) NOT NULL COMMENT 'Min(기준일보유,현재보유,신청) 결과',
    processed_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '입고일',
    PRIMARY KEY (inbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='입고 요청';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE inbound_detail (
    inbound_detail_id         BIGINT        NOT NULL AUTO_INCREMENT,
    inbound_id                BIGINT        NOT NULL,
    foreign_product_id        BIGINT        NOT NULL,
    source_broker             VARCHAR(20)   NULL     COMMENT '타사 대체입고 시 출처 증권사명(source_general_account_id와 상호배타)',
    source_general_account_id BIGINT        NULL     COMMENT '당사 일반계좌 출처 시 general_account_id(FK없음). 타사면 NULL. 배당 비율배분 기준',
    account_type              VARCHAR(30)   NULL     COMMENT '출처 일반계좌 종류(BROKERAGE/CMA/IRP/PENSION_SAVINGS/ISA). source_general_account_id가 NULL이면 같이 NULL',
    recorded_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '기록일',
    purchase_date             DATETIME      NOT NULL COMMENT '매수일자',
    purchase_price            DECIMAL(15,4) NOT NULL COMMENT '매수단가',
    purchase_currency         VARCHAR(10)   NOT NULL COMMENT '취득통화',
    purchase_fx_rate          DECIMAL(15,4) NOT NULL COMMENT '매수 결제일 기준환율',
    qty                       DECIMAL(15,4) NOT NULL COMMENT '수량',
    current_qty               DECIMAL(15,4) NOT NULL COMMENT '잔량(애플리케이션에서 초기값 = qty 세팅)',
    PRIMARY KEY (inbound_detail_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='입고 상세(lot)';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE inbound_min (
    inbound_min_id    BIGINT NOT NULL AUTO_INCREMENT,
    inbound_detail_id BIGINT NOT NULL COMMENT '결과 lot',
    requested_qty     DECIMAL(15,4) NULL COMMENT '요청 수량',
    approved_qty      DECIMAL(15,4) NULL COMMENT '보유중 수량',
    snapshot_qty      DECIMAL(15,4) NULL COMMENT '기준일 수량',
    PRIMARY KEY (inbound_min_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='3-way Min 산식 근거';

-- 대상상품 판별 결과(G2, 스냅샷)
CREATE TABLE target_product_judgement (
    judgement_id         BIGINT        NOT NULL AUTO_INCREMENT,
    mydata_trade_id      BIGINT        NOT NULL COMMENT 'mydata_trade.trade_id 참조(다른 DB, FK 불가)',
    ci_hash              VARCHAR(64)   NOT NULL COMMENT '판정 대상 고객(customer.ci_hash와 매칭)',
    stock_type           VARCHAR(15)   NOT NULL COMMENT 'FOREIGN_STOCK/ETF/ETN/FUND',
    fund_code            VARCHAR(12)   NULL COMMENT 'stock_type=FUND인 경우만',
    fund_name            VARCHAR(100)  NULL COMMENT '판정 시점 스냅샷',
    ticker               VARCHAR(20)   NULL COMMENT '종목 티커(stock_type이 FOREIGN_STOCK/ETF/ETN인 경우만)',
    is_target            BOOLEAN       NOT NULL COMMENT 'G2 판정 결과(대상상품 여부)',
    foreign_stock_ratio  DECIMAL(5,2)  NULL COMMENT '판정 시점 스냅샷',
    inception_date       DATE          NULL COMMENT '판정 시점 스냅샷',
    judged_at            DATETIME      NOT NULL COMMENT '판정 시각',
    trade_type           VARCHAR(15)   NOT NULL COMMENT '원본 거래유형(BUY/SELL/INHERITANCE/GIFT), 감사용',
    amount               DECIMAL(15,2) NOT NULL COMMENT '원본 거래금액(항상 양수), 감사용',
    trade_date           DATE          NOT NULL COMMENT 'F1 시기별 가중치 판정용',
    net_buy_amount       DECIMAL(15,2) NOT NULL COMMENT 'F3이 바로 합산할 부호처리된 금액(SELL만 음수, 나머지 양수)',
    PRIMARY KEY (judgement_id),
    CONSTRAINT uq_target_product__trade UNIQUE (mydata_trade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G2 대상상품 판별 결과(스냅샷)';

-- G1 mydata 거래 동기화 커서(고객별 마지막 처리 거래일 — 반복 동기화 시 fromDate로 사용)
CREATE TABLE external_trade_sync_cursor (
                                            cursor_id              BIGINT   NOT NULL AUTO_INCREMENT,
                                            customer_id            BIGINT   NOT NULL COMMENT '동기화 대상 고객',
                                            last_synced_trade_date DATE     NULL     COMMENT '마지막으로 처리한 거래의 trade_date. 다음 동기화 시 MydataTradeRequestDTO.fromDate로 사용(경계일 중복은 judge() 전 존재확인으로 방지)',
                                            updated_at             DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '커서 갱신 시각',
                                            PRIMARY KEY (cursor_id),
                                            UNIQUE KEY uk_external_trade_sync_cursor__customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G1 mydata 거래 동기화 커서(고객별 마지막 처리 거래일)';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE outbound (
    outbound_id       BIGINT        NOT NULL AUTO_INCREMENT,
    inbound_detail_id BIGINT        NOT NULL,
    qty               DECIMAL(15,4) NOT NULL COMMENT '출고수량',
    reason            VARCHAR(50)   NULL     COMMENT '매도의사철회/한도초과분/배당해외주식 등',
    processed_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (outbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='출고';

-- ---------------------------------------------------------------------
-- 매도 / 환전
-- ---------------------------------------------------------------------
-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE sell_order (
    order_id          BIGINT        NOT NULL AUTO_INCREMENT,
    account_id        BIGINT        NOT NULL COMMENT '매도 시도 계좌 (거부 건은 lot이 없어 간접조회 불가하므로 직접 저장)',
    foreign_product_id BIGINT       NOT NULL COMMENT '매도 시도 종목 (거부 건은 lot이 없어 간접조회 불가하므로 직접 저장)',
    inbound_detail_id BIGINT        NULL     COMMENT '매도 대상 lot (한 lot이 여러 매도주문에 걸쳐 나뉠 수 있음 — FIFO 분할매도). REJECTED면 NULL',
    sell_qty          DECIMAL(15,4) NOT NULL COMMENT '매도수량',
    base_price        DECIMAL(15,4) NOT NULL COMMENT '매도기준가(전일종가 x 환율)',
    processed_at      DATETIME      NULL     DEFAULT CURRENT_TIMESTAMP COMMENT '매도결제일',
    status            VARCHAR(10)   NOT NULL COMMENT 'RECEIVED/EXECUTED/REJECTED',
    settlement_fx_rate DECIMAL(15,4) NULL    COMMENT '매도 결제일 기준환율',
    PRIMARY KEY (order_id),
    CONSTRAINT chk_sell_order_status CHECK (status IN ('RECEIVED','EXECUTED','REJECTED')),
    CONSTRAINT chk_sell_order_lot_consistency CHECK (
        (status = 'REJECTED' AND inbound_detail_id IS NULL)
        OR
        (status != 'REJECTED' AND inbound_detail_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='매도 주문';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE krw_exchange (
    exchange_id        BIGINT        NOT NULL AUTO_INCREMENT,
    account_id         BIGINT        NOT NULL,
    order_id           BIGINT        NOT NULL,
    provisional_amount DECIMAL(15,2) NOT NULL COMMENT '가환전액',
    provisional_at     DATETIME      NOT NULL COMMENT '가환전일시',
    final_rate         DECIMAL(15,6) NULL     COMMENT '확정환율',
    final_amount       DECIMAL(15,0) NULL     COMMENT '확정환전액(=납입일 기준금액)',
    final_at           DATETIME      NULL     COMMENT 'T+2 결제일 - 1년 인출 시계 기준점',
    settlement_status  VARCHAR(12)   NOT NULL COMMENT 'PROVISIONAL/FINALIZED',
    PRIMARY KEY (exchange_id),
    UNIQUE KEY uk_krw_exchange_order_id (order_id),
    CONSTRAINT chk_krw_exchange_status CHECK (settlement_status IN ('PROVISIONAL','FINALIZED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='원화 환전(가정산/확정산)';

-- ---------------------------------------------------------------------
-- 인출
-- ---------------------------------------------------------------------
-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE left_amount (
    left_amount_id BIGINT        NOT NULL AUTO_INCREMENT,
    exchange_id    BIGINT        NOT NULL,
    cur_amount     DECIMAL(15,0) NOT NULL COMMENT '인출 후 남은 정산건별 원금(초기값 = krw_exchange.final_amount, 애플리케이션 세팅)',
    PRIMARY KEY (left_amount_id),
    UNIQUE KEY uk_left_amount_exchange_id (exchange_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='정산건별 잔여 원금(FIFO)';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE withdrawal (
    withdrawal_id                  BIGINT        NOT NULL AUTO_INCREMENT,
    account_id                     BIGINT        NOT NULL,
    requested_amount               DECIMAL(15,2) NOT NULL COMMENT '인출요청금액',
    processed_at                   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '인출 요청 일시',
    destination_account_no         VARCHAR(30)   NOT NULL COMMENT '이체 목적지 계좌번호(스냅샷)',
    destination_general_account_id BIGINT        NOT NULL COMMENT '인출 목적지 general_account 참조값(FK없음). 비율배분 없이 단일 선택',
    status                         VARCHAR(12)   NOT NULL DEFAULT 'REQUESTED' COMMENT 'REQUESTED/COMPLETED/CANCELLED/FAILED',
    PRIMARY KEY (withdrawal_id),
    CONSTRAINT chk_withdrawal_status CHECK (status IN ('REQUESTED','COMPLETED','CANCELLED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='인출 요청';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE withdrawal_allocation (
    allocation_id    BIGINT        NOT NULL AUTO_INCREMENT,
    withdrawal_id    BIGINT        NOT NULL,
    left_amount_id   BIGINT        NULL     COMMENT 'FIFO 대상 정산건',
    allocated_amount DECIMAL(15,2) NOT NULL,
    withdrawal_at    DATETIME      NOT NULL COMMENT '인출 일시',
    type             VARCHAR(30)   NOT NULL COMMENT 'EARNINGS_ONLY/MATURED_PRINCIPAL_INCLUDED/IMMATURE_PRINCIPAL_INCLUDED',
    PRIMARY KEY (allocation_id),
    CONSTRAINT chk_wa_type CHECK (type IN ('EARNINGS_ONLY','MATURED_PRINCIPAL_INCLUDED','IMMATURE_PRINCIPAL_INCLUDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='인출 FIFO 회계 분개';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE account_closure_request (
    closure_request_id             BIGINT       NOT NULL AUTO_INCREMENT,
    account_id                     BIGINT       NOT NULL,
    destination_general_account_id BIGINT       NOT NULL COMMENT '강제인출 목적지 general_account 참조값(FK없음)',
    early_withdrawal_agreed        BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '조기인출 및 세제혜택 취소 동의',
    status                         VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED' COMMENT 'REQUESTED/COMPLETED/REJECTED',
    requested_at                   DATETIME      NOT NULL COMMENT '해지 신청 업무시각',
    processed_at                   DATETIME      NULL COMMENT '해지 또는 반려 처리 업무시각',
    processed_by                   BIGINT        NULL COMMENT '처리 관리자',
    rejection_reason               VARCHAR(255) NULL COMMENT '해지 반려 사유',
    withdrawal_id                  BIGINT        NULL COMMENT '강제인출 결과. 잔액이 0원이면 NULL',
    PRIMARY KEY (closure_request_id),
    KEY idx_acr_account_status (account_id, status),
    CONSTRAINT chk_acr_status CHECK (status IN ('REQUESTED','COMPLETED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RIA 계좌 해지 신청';

-- ---------------------------------------------------------------------
-- 세금 계산
-- ---------------------------------------------------------------------
-- [SEED] data-generator가 채우는 테이블. 컬럼 추가/삭제 시 생성기 INSERT(컬럼목록+값)도 수정 후 재생성 필요 (seed.sql 직접 편집 금지).
CREATE TABLE tax_rule (
    rule_id    BIGINT        NOT NULL AUTO_INCREMENT,
    rule_type  VARCHAR(20)   NOT NULL COMMENT 'RELIEF_RATE/DEPOSIT_LIMIT/HOLDING_PERIOD/BASIC_DEDUCTION/TAX_RATE',
    rule_value DECIMAL(15,4) NOT NULL,
    valid_from DATE          NOT NULL,
    valid_to   DATE          NOT NULL COMMENT '열린구간은 9999-12-31',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(30)   NULL,
    PRIMARY KEY (rule_id),
    CONSTRAINT chk_tax_rule_type CHECK (rule_type IN ('RELIEF_RATE','DEPOSIT_LIMIT','HOLDING_PERIOD','BASIC_DEDUCTION','TAX_RATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='effective-dated 세금 규칙';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE tax_calculation (
    calc_id       BIGINT        NOT NULL AUTO_INCREMENT,
    account_id    BIGINT        NOT NULL,
    calculated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    basis_type    VARCHAR(30)   NOT NULL COMMENT 'FINAL_REPORT(확정신고)/EARLY_WITHDRAWAL_CLAWBACK(조기인출추징). 미리보기는 저장 안 함',
    weighted_sell            DECIMAL(15,2) NOT NULL COMMENT '[1] 가중매도금액(조정비율 분모)',
    original_gain_amount     DECIMAL(15,2) NOT NULL COMMENT '비가중 총양도소득([5]단계 기준)',
    weighted_gain            DECIMAL(15,2) NOT NULL COMMENT '[1] 가중양도소득 = 조정전공제액',
    weighted_external_amount DECIMAL(15,2) NOT NULL COMMENT '[2] 외부 순매수 가중합산(myData 스냅샷)',
    adjust_ratio             DECIMAL(7,4)  NOT NULL COMMENT '[3] 조정비율(0~1 clamp)',
    final_deduction          DECIMAL(15,2) NOT NULL COMMENT '[4] 최종공제액',
    final_tax                DECIMAL(15,2) NOT NULL COMMENT '[5] 최종세액',
    PRIMARY KEY (calc_id),
    UNIQUE KEY uk_tax_calc__account_basis (account_id, basis_type),
    CONSTRAINT chk_tax_calc_basis CHECK (basis_type IN ('FINAL_REPORT','EARLY_WITHDRAWAL_CLAWBACK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='세액 계산 확정 근거 스냅샷';

-- [NO-SEED] 계산 데이터(배치가 생성). 표시용 캐시이며 확정 근거가 아니다.
-- 계좌 목록 화면에서 행마다 실시간 계산이 불가능해 매일 배치가 UPSERT한다.
-- 확정 근거는 tax_calculation, 상세 내역은 실시간 조회(GET /api/tax/preview)가 담당.
CREATE TABLE tax_snapshot (
    snapshot_id   BIGINT        NOT NULL AUTO_INCREMENT,
    account_id    BIGINT        NOT NULL COMMENT '계좌당 1행. 배치가 UPSERT',
    calculated_at DATETIME      NOT NULL COMMENT '배치 실행 시각. 화면에 "OO 기준"으로 노출',
    weighted_sell            DECIMAL(15,2) NOT NULL COMMENT '[1] 가중매도금액',
    original_gain_amount     DECIMAL(15,2) NOT NULL COMMENT '비가중 총양도소득',
    weighted_gain            DECIMAL(15,2) NOT NULL COMMENT '[1] 가중양도소득 = 조정전공제액',
    weighted_external_amount DECIMAL(15,2) NOT NULL COMMENT '[2] 외부 순매수 가중합산',
    adjust_ratio             DECIMAL(7,4)  NOT NULL COMMENT '[3] 조정비율',
    final_deduction          DECIMAL(15,2) NOT NULL COMMENT '[4] 최종공제액',
    final_tax                DECIMAL(15,2) NOT NULL COMMENT '[5] 최종세액',
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_tax_snapshot__account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='세액 스냅샷(표시용 캐시. 확정 근거 아님)';

-- ---------------------------------------------------------------------
-- 정산 배치
-- ---------------------------------------------------------------------
-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE settlement_batch (
    batch_id    BIGINT      NOT NULL AUTO_INCREMENT,
    executed_at DATETIME    NOT NULL COMMENT '배치 실행일시(일단위=익일정산)',
    status      VARCHAR(10) NOT NULL COMMENT 'RUNNING/COMPLETED/FAILED',
    run_id      VARCHAR(50) NOT NULL COMMENT '현재 실행 Job ID',
    failure_message VARCHAR(500) NULL COMMENT '배치 실행 실패 사유',
    PRIMARY KEY (batch_id),
    UNIQUE KEY uk_settlement_batch_run_id (run_id),
    CONSTRAINT chk_settlement_batch_status CHECK (status IN ('RUNNING','COMPLETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='정산 배치';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE settlement_item (
    item_id      BIGINT      NOT NULL AUTO_INCREMENT,
    batch_id     BIGINT      NOT NULL,
    exchange_id  BIGINT      NOT NULL COMMENT '처리 대상 환전 ID',
    result       VARCHAR(10) NULL     COMMENT 'NULL(대기)/SUCCESS/FAILED',
    processed_at DATETIME    NULL     COMMENT '개별 확정산 완료일시',
    failure_code VARCHAR(50) NULL     COMMENT '실패 분류 코드',
    failure_message VARCHAR(500) NULL COMMENT '실패 상세 사유',
    PRIMARY KEY (item_id),
    KEY idx_settlement_item_batch_result (batch_id, result),
    KEY idx_settlement_item_exchange_result (exchange_id, result),
    CONSTRAINT chk_settlement_item_result CHECK (result IN ('SUCCESS','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='정산 배치 항목';

-- 정산 배치 행 잠금 (같은 영업일 배치 중복 실행 방지: SELECT ... FOR UPDATE 대상)
-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE settlement_batch_guard (
    business_date DATE     NOT NULL COMMENT '정산 대상 영업일(행 잠금 키)',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='정산 배치 행 잠금';

-- ---------------------------------------------------------------------
-- 관리자
-- ---------------------------------------------------------------------
-- [SEED] data-generator가 채우는 테이블. 컬럼 추가/삭제 시 생성기 INSERT(컬럼목록+값)도 수정 후 재생성 필요 (seed.sql 직접 편집 금지).
CREATE TABLE admin_user (
    admin_id      BIGINT       NOT NULL AUTO_INCREMENT,
    login_id      VARCHAR(50)  NOT NULL,
    name          VARCHAR(50)  NOT NULL COMMENT '관리자 이름',
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'VIEWER' COMMENT 'VIEWER(조회전용)/REVIEWER(심사담당)/SETTLEMENT(정산담당)/ADMIN(최고관리자). 계정 생성 시 기본 VIEWER, ADMIN이 추후 승격',
    PRIMARY KEY (admin_id),
    UNIQUE KEY uk_admin_login_id (login_id),
    CONSTRAINT chk_admin_role CHECK (role IN ('VIEWER','REVIEWER','SETTLEMENT','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='백오피스 관리자';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE audit_log (
    audit_id     BIGINT      NOT NULL AUTO_INCREMENT,
    admin_id     BIGINT      NOT NULL,
    target_table VARCHAR(50) NOT NULL COMMENT '대상테이블',
    target_pk    VARCHAR(50) NOT NULL COMMENT '대상 PK값',
    before_value TEXT        NULL     COMMENT '변경전값',
    after_value  TEXT        NULL     COMMENT '변경후값',
    reason_code  VARCHAR(30) NULL     COMMENT '사유코드',
    processed_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='감사 로그';

-- =====================================================================
-- FOREIGN KEYS (시스템 내부만. myData/증권사 참조값은 FK 없음)
-- =====================================================================
ALTER TABLE customer_auth          ADD CONSTRAINT fk_customer_auth__customer          FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE customer_auth_password ADD CONSTRAINT fk_cap__customer_auth               FOREIGN KEY (auth_id)              REFERENCES customer_auth (auth_id);
ALTER TABLE account                ADD CONSTRAINT fk_account__customer                FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE account_status_log     ADD CONSTRAINT fk_asl__account                     FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE account_benefit_log    ADD CONSTRAINT fk_abl__account                     FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE domestic_stock_balance ADD CONSTRAINT fk_dsb__account                     FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE domestic_stock_balance ADD CONSTRAINT fk_dsb__domestic_product            FOREIGN KEY (domestic_product_id)  REFERENCES domestic_product (domestic_product_id);
ALTER TABLE inbound                ADD CONSTRAINT fk_inbound__account                 FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE inbound_detail         ADD CONSTRAINT fk_inbound_detail__inbound          FOREIGN KEY (inbound_id)           REFERENCES inbound (inbound_id);
ALTER TABLE inbound_detail         ADD CONSTRAINT fk_inbound_detail__foreign_product  FOREIGN KEY (foreign_product_id)   REFERENCES foreign_product (foreign_product_id);
ALTER TABLE inbound_min            ADD CONSTRAINT fk_inbound_min__inbound_detail       FOREIGN KEY (inbound_detail_id)    REFERENCES inbound_detail (inbound_detail_id);
ALTER TABLE external_trade_sync_cursor ADD CONSTRAINT fk_etsc__customer                FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE outbound               ADD CONSTRAINT fk_outbound__inbound_detail          FOREIGN KEY (inbound_detail_id)    REFERENCES inbound_detail (inbound_detail_id);
ALTER TABLE sell_order             ADD CONSTRAINT fk_sell_order__inbound_detail        FOREIGN KEY (inbound_detail_id)    REFERENCES inbound_detail (inbound_detail_id);
ALTER TABLE sell_order             ADD CONSTRAINT fk_sell_order__account               FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE sell_order             ADD CONSTRAINT fk_sell_order__foreign_product       FOREIGN KEY (foreign_product_id)   REFERENCES foreign_product (foreign_product_id);
ALTER TABLE krw_exchange           ADD CONSTRAINT fk_krw_exchange__account             FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE krw_exchange           ADD CONSTRAINT fk_krw_exchange__sell_order          FOREIGN KEY (order_id)             REFERENCES sell_order (order_id);
ALTER TABLE left_amount            ADD CONSTRAINT fk_left_amount__krw_exchange         FOREIGN KEY (exchange_id)          REFERENCES krw_exchange (exchange_id);
ALTER TABLE withdrawal             ADD CONSTRAINT fk_withdrawal__account               FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE withdrawal_allocation  ADD CONSTRAINT fk_wa__withdrawal                    FOREIGN KEY (withdrawal_id)        REFERENCES withdrawal (withdrawal_id);
ALTER TABLE withdrawal_allocation  ADD CONSTRAINT fk_wa__left_amount                   FOREIGN KEY (left_amount_id)       REFERENCES left_amount (left_amount_id);
ALTER TABLE account_closure_request ADD CONSTRAINT fk_acr__account                      FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE account_closure_request ADD CONSTRAINT fk_acr__admin_user                   FOREIGN KEY (processed_by)         REFERENCES admin_user (admin_id);
ALTER TABLE account_closure_request ADD CONSTRAINT fk_acr__withdrawal                   FOREIGN KEY (withdrawal_id)        REFERENCES withdrawal (withdrawal_id);
ALTER TABLE tax_calculation        ADD CONSTRAINT fk_tax_calc__account                 FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE tax_snapshot           ADD CONSTRAINT fk_tax_snapshot__account             FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE settlement_item        ADD CONSTRAINT fk_settlement_item__batch            FOREIGN KEY (batch_id)             REFERENCES settlement_batch (batch_id);
ALTER TABLE settlement_item        ADD CONSTRAINT fk_settlement_item__krw_exchange     FOREIGN KEY (exchange_id)          REFERENCES krw_exchange (exchange_id);
ALTER TABLE audit_log              ADD CONSTRAINT fk_audit_log__admin_user             FOREIGN KEY (admin_id)             REFERENCES admin_user (admin_id);
