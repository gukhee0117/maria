-- =====================================================================
-- RIA 관리자 시스템 DDL (MariaDB / MySQL 문법)
-- ERDCloud export를 실행 가능한 DDL로 변환
-- 시스템 경계(§8-8): myData/증권사 참조는 값만 저장, FK 없음
-- 변경: domestic_stock_balance를 증권사 -> RIA로 이동(account/domestic_product FK 복구)
-- 변경(2차, 이슈 #308): 확정 ERD(docs/phase2-erd-changes.md, ERDCloud ver5) 반영 - 신규 19개 테이블, 기존 테이블 컬럼·제약 변경, FK 29개 추가
-- 변경(2차, 10-28): customer.investor_type_public 추가, customer_login_history 신규, news_item에 category·picked_slot 추가 + ticker NULL 허용 + (ticker, picked_date) UNIQUE 삭제
-- =====================================================================

CREATE DATABASE IF NOT EXISTS ria_admin DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;
USE ria_admin;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS community_report;
DROP TABLE IF EXISTS community_comment_like;
DROP TABLE IF EXISTS community_post_reaction;
DROP TABLE IF EXISTS community_comment;
DROP TABLE IF EXISTS community_post;
DROP TABLE IF EXISTS stock_recommendation;
DROP TABLE IF EXISTS risk_grade_sync_state;
DROP TABLE IF EXISTS stock_risk_grade;
DROP TABLE IF EXISTS domestic_product_theme;
DROP TABLE IF EXISTS stock_theme;
DROP TABLE IF EXISTS stock_package_item;
DROP TABLE IF EXISTS domestic_order;
DROP TABLE IF EXISTS stock_package;
DROP TABLE IF EXISTS news_item;
DROP TABLE IF EXISTS rag_chunk;
DROP TABLE IF EXISTS customer_consent;
DROP TABLE IF EXISTS investor_assessment;
DROP TABLE IF EXISTS document_issue;
DROP TABLE IF EXISTS fund_flow_monthly;
DROP TABLE IF EXISTS notification;
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
DROP TABLE IF EXISTS target_product_judgement_failure;
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
DROP TABLE IF EXISTS customer_login_history;
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
    nickname      VARCHAR(20) NULL     COMMENT '채팅방 닉네임',
    birth_date    DATE        NOT NULL COMMENT '생년월일',
    phone         VARCHAR(20) NULL     COMMENT '연락처',
    email         VARCHAR(100) NULL    COMMENT '이메일',
    investor_type VARCHAR(20) NOT NULL COMMENT '투자자유형',
    investor_type_public BOOLEAN NOT NULL DEFAULT 0 COMMENT '채팅방에 투자성향 공개 여부(기본 비공개)',
    ci_hash       VARCHAR(64) NOT NULL COMMENT 'HMAC-SHA256(정규화 주민번호 13자리, 공유 PEPPER). 원본 미저장. 3개 시스템 공유키(CI 역할)',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일시',
    PRIMARY KEY (customer_id),
    UNIQUE KEY uk_customer_ci_hash (ci_hash),
    UNIQUE KEY uk_customer_nickname (nickname),
    CONSTRAINT chk_customer_investor_type CHECK (investor_type IN ('STABLE','CONSERVATIVE','NEUTRAL','ACTIVE','AGGRESSIVE','UNASSIGNED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RIA 고객';

-- [NO-SEED] 계산 데이터(서비스/골든 시나리오로 생성). 생성기 무관.
CREATE TABLE customer_auth (
    auth_id       BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id   BIGINT      NOT NULL,
    login_id      VARCHAR(50) NOT NULL COMMENT '로그인ID',
    type          VARCHAR(10) NOT NULL COMMENT 'LOCAL/GOOGLE/KAKAO/NAVER',
    last_login_at DATETIME    NULL     COMMENT '마지막 로그인 일시',
    PRIMARY KEY (auth_id),
    UNIQUE KEY uk_customer_auth_type_login (type, login_id),
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
    dart_corp_code      CHAR(8)      NULL     COMMENT 'OpenDART 회사 고유번호(STOCK만). 끼루 종목 정보 답글용',
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
    registrable_stock_id      BIGINT        NULL     COMMENT '증권사 적격 원본 ID(외부 참조, FK 없음). 기존 데이터는 NULL 허용',
    source_broker             VARCHAR(20)   NULL     COMMENT '최초 타사 출처 증권사명. 당사 출처 일반계좌와 함께 보존 가능',
    source_general_account_id BIGINT        NULL     COMMENT '이번 RIA 입고의 출처 당사 일반계좌 ID(외부 참조, FK 없음). 배당 비율배분 기준',
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

-- G1 거래 판정 실패 재시도 카운트(N회 초과 시 영구실패로 커서를 넘김)
CREATE TABLE target_product_judgement_failure (
    failure_id      BIGINT        NOT NULL AUTO_INCREMENT,
    mydata_trade_id BIGINT        NOT NULL COMMENT 'mydata_trade.trade_id 참조(다른 DB, FK 불가)',
    ci_hash         VARCHAR(64)   NOT NULL,
    trade_date      DATE          NOT NULL,
    failure_count   INT           NOT NULL COMMENT '누적 실패 횟수',
    last_error      VARCHAR(500)  NULL COMMENT '가장 최근 실패 원인(예외 메시지)',
    first_failed_at DATETIME      NOT NULL,
    last_failed_at  DATETIME      NOT NULL,
    PRIMARY KEY (failure_id),
    CONSTRAINT uq_target_product_failure__trade UNIQUE (mydata_trade_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='G1 거래 판정 실패 재시도 카운트';

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
    rule_type  VARCHAR(20)   NOT NULL COMMENT 'RELIEF_RATE/DEPOSIT_LIMIT/HOLDING_PERIOD/BASIC_DEDUCTION/TAX_RATE/ENROLLMENT_OPEN',
    rule_value DECIMAL(15,4) NOT NULL,
    valid_from DATE          NOT NULL,
    valid_to   DATE          NOT NULL COMMENT '열린구간은 9999-12-31',
    created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(30)   NULL,
    PRIMARY KEY (rule_id),
    CONSTRAINT chk_tax_rule_type CHECK (rule_type IN ('RELIEF_RATE','DEPOSIT_LIMIT','HOLDING_PERIOD','BASIC_DEDUCTION','TAX_RATE','ENROLLMENT_OPEN'))
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
    actor_type   VARCHAR(10) NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN/CUSTOMER/SYSTEM',
    actor_id     BIGINT      NULL     COMMENT 'CUSTOMER면 customer_id, SYSTEM이면 NULL',
    admin_id     BIGINT      NULL     COMMENT '관리자 행위일 때만. 새 코드는 actor_type+actor_id',
    target_table VARCHAR(50) NOT NULL COMMENT '대상테이블',
    target_pk    VARCHAR(50) NOT NULL COMMENT '대상 PK값',
    before_value TEXT        NULL     COMMENT '변경전값',
    after_value  TEXT        NULL     COMMENT '변경후값',
    reason_code  VARCHAR(30) NULL     COMMENT '사유코드',
    processed_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '처리시각(비즈니스 시계)',
    recorded_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '기록시각(실제 DB 시각)',
    PRIMARY KEY (audit_id),
    CONSTRAINT chk_audit_actor_type CHECK (actor_type IN ('ADMIN','CUSTOMER','SYSTEM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='감사 로그';

-- ---------------------------------------------------------------------
-- 2차 신규 테이블 (maria 확정 ERD, 이슈 #308)
-- ---------------------------------------------------------------------
-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE domestic_order (
    domestic_order_id   BIGINT        NOT NULL AUTO_INCREMENT,
    account_id          BIGINT        NOT NULL,
    domestic_product_id BIGINT        NOT NULL,
    stock_package_id    BIGINT        NULL     COMMENT '패키지 주문이면 패키지, 아니면 NULL',
    side                VARCHAR(4)    NOT NULL COMMENT 'BUY/SELL',
    qty                 DECIMAL(15,4) NOT NULL COMMENT '정수 주만',
    price               DECIMAL(15,4) NULL     COMMENT '체결가(증권사 현재가)',
    amount              DECIMAL(15,0) NULL     COMMENT '예수금 변동액(원)',
    channel             VARCHAR(10)   NOT NULL COMMENT 'GENERATOR/CUSTOMER',
    idempotency_key     VARCHAR(64)   NOT NULL COMMENT '증권사 주문번호 또는 화면 UUID',
    status              VARCHAR(10)   NOT NULL COMMENT 'EXECUTED/REJECTED',
    reject_reason       VARCHAR(30)   NULL,
    securities_trade_id BIGINT        NULL     COMMENT '증권사 domestic_trade.trade_id (FK 없음)',
    processed_at        DATETIME      NOT NULL COMMENT '업무시계',
    recorded_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '실제 시각',
    PRIMARY KEY (domestic_order_id),
    UNIQUE KEY uk_domestic_order_idem (account_id, idempotency_key),
    CONSTRAINT chk_do_side    CHECK (side IN ('BUY','SELL')),
    CONSTRAINT chk_do_channel CHECK (channel IN ('GENERATOR','CUSTOMER')),
    CONSTRAINT chk_do_status  CHECK (status IN ('EXECUTED','REJECTED')),
    CONSTRAINT chk_do_qty     CHECK (qty > 0 AND qty = FLOOR(qty)),
    CONSTRAINT chk_do_exec    CHECK (status = 'REJECTED' OR (price IS NOT NULL AND amount IS NOT NULL)),
    CONSTRAINT chk_do_package CHECK (channel = 'CUSTOMER' OR stock_package_id IS NULL),
    CONSTRAINT chk_do_price   CHECK (price IS NULL OR price > 0),
    CONSTRAINT chk_do_reason  CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RIA 국내 주문 원장';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE stock_package (
    stock_package_id BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id      BIGINT        NOT NULL,
    name             VARCHAR(50)   NOT NULL,
    total_amount     DECIMAL(15,0) NULL     COMMENT '만들 때 입력한 총 투자금액(원)',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_package_id),
    CONSTRAINT chk_sp_amount CHECK (total_amount IS NULL OR total_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='고객 주식 패키지';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE stock_package_item (
    stock_package_item_id   BIGINT       NOT NULL AUTO_INCREMENT,
    stock_package_id        BIGINT       NOT NULL,
    domestic_product_id     BIGINT       NOT NULL,
    weight                  DECIMAL(5,2) NOT NULL COMMENT '목표 비중%, 패키지 합 100은 서비스 검증',
    unsuitable_confirmed_at DATETIME     NULL     COMMENT '부적합 확인 시각(준칙 제6조③)',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_package_item_id),
    UNIQUE KEY uk_spi (stock_package_id, domestic_product_id),
    CONSTRAINT chk_spi_weight CHECK (weight > 0 AND weight <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주식 패키지 구성 종목';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE stock_theme (
    stock_theme_id BIGINT      NOT NULL AUTO_INCREMENT,
    name           VARCHAR(30) NOT NULL,
    PRIMARY KEY (stock_theme_id),
    UNIQUE KEY uk_stock_theme_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='테마(자체 분류)';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE domestic_product_theme (
    domestic_product_id BIGINT NOT NULL,
    stock_theme_id      BIGINT NOT NULL,
    PRIMARY KEY (domestic_product_id, stock_theme_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='종목-테마 연결';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE stock_risk_grade (
    domestic_product_id BIGINT       NOT NULL,
    var_975             DECIMAL(7,4) NOT NULL,
    risk_grade          TINYINT      NOT NULL,
    volatility          DECIMAL(7,4) NOT NULL,
    price_from          DATE         NOT NULL,
    price_to            DATE         NOT NULL,
    calculated_at       DATETIME     NOT NULL COMMENT 'Python 계산 시각',
    synced_at           DATETIME     NOT NULL COMMENT 'maria 저장 시각',
    analysis_version    BIGINT       NOT NULL DEFAULT 0 COMMENT '0=이관된 미확인 버전, 1 이상=Python 검증 게시 버전',
    PRIMARY KEY (domestic_product_id),
    CONSTRAINT chk_srg_analysis_version CHECK (analysis_version >= 0),
    CONSTRAINT chk_srg_grade CHECK (risk_grade BETWEEN 1 AND 6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='종목 위험등급(Python risk 사본)';

CREATE TABLE risk_grade_sync_state (
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
INSERT INTO risk_grade_sync_state(state_id) VALUES (1);

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE stock_recommendation (
    stock_recommendation_id BIGINT       NOT NULL AUTO_INCREMENT,
    request_id              VARCHAR(36)  NOT NULL,
    customer_id             BIGINT       NOT NULL,
    domestic_product_id     BIGINT       NOT NULL,
    rank_no                 TINYINT      NOT NULL,
    recommend_date          DATE         NOT NULL COMMENT '추천 업무일',
    investor_type           VARCHAR(20)  NOT NULL COMMENT '스냅샷',
    allowed_max_grade       TINYINT      NOT NULL COMMENT '스냅샷',
    risk_grade              TINYINT      NOT NULL COMMENT '스냅샷',
    reason                  VARCHAR(500) NULL,
    source                  VARCHAR(10)  NOT NULL COMMENT 'AI/RULE',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (stock_recommendation_id),
    UNIQUE KEY uk_sr_day_rank    (customer_id, recommend_date, rank_no),
    UNIQUE KEY uk_sr_day_product (customer_id, recommend_date, domestic_product_id),
    CONSTRAINT chk_sr_rank   CHECK (rank_no BETWEEN 1 AND 3),
    CONSTRAINT chk_sr_source CHECK (source IN ('AI','RULE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='홈 종목 추천 기록';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE news_item (
    news_item_id  BIGINT        NOT NULL AUTO_INCREMENT,
    ticker        VARCHAR(20)   NULL     COMMENT 'RIA·세제 기사는 종목이 없어 NULL',
    category      VARCHAR(10)   NOT NULL COMMENT 'MY_STOCK(내 종목)/RIA_TAX(RIA·세제)',
    title         VARCHAR(300)  NOT NULL,
    description   VARCHAR(1000) NOT NULL COMMENT '발췌문(본문 저장 안 함)',
    link          VARCHAR(500)  NOT NULL,
    original_link VARCHAR(500)  NULL,
    published_at  DATETIME      NOT NULL,
    related_count INT           NOT NULL DEFAULT 1 COMMENT '같은 사건 기사 수(이슈 점수)',
    picked_date   DATE          NULL     COMMENT '대표 기사로 뽑힌 날',
    picked_slot   VARCHAR(2)    NULL     COMMENT '뽑힌 회차 AM(오전)/PM(오후). picked_date와 함께 채우거나 함께 비움',
    summary       VARCHAR(1000) NULL     COMMENT '검증 통과한 AI 요약',
    summary_model VARCHAR(50)   NULL,
    collected_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (news_item_id),
    UNIQUE KEY uk_news_item_link (link),
    CONSTRAINT chk_ni_related  CHECK (related_count >= 1),
    CONSTRAINT chk_ni_category CHECK (category IN ('MY_STOCK','RIA_TAX')),
    CONSTRAINT chk_ni_slot     CHECK (picked_slot IS NULL OR picked_slot IN ('AM','PM')),
    CONSTRAINT chk_ni_pick     CHECK ((picked_date IS NULL) = (picked_slot IS NULL)),
    CONSTRAINT chk_ni_ticker   CHECK (category <> 'MY_STOCK' OR ticker IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='뉴스·기사 요약(내 종목/RIA·세제)';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE community_post (
    community_post_id BIGINT       NOT NULL AUTO_INCREMENT,
    ticker            VARCHAR(20)  NULL     COMMENT '종목 토론이면 종목코드, 일반 글이면 NULL',
    customer_id       BIGINT       NOT NULL COMMENT '작성자(응답에는 닉네임만)',
    title             VARCHAR(100) NOT NULL,
    content           TEXT         NOT NULL,
    ask_kiru          BOOLEAN      NOT NULL DEFAULT FALSE,
    status            VARCHAR(10)  NOT NULL DEFAULT 'VISIBLE' COMMENT 'VISIBLE/HIDDEN/DELETED',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (community_post_id),
    CONSTRAINT chk_cp_status CHECK (status IN ('VISIBLE','HIDDEN','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='채팅방(종목 토론) 글';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE community_comment (
    community_comment_id BIGINT      NOT NULL AUTO_INCREMENT,
    community_post_id    BIGINT      NOT NULL,
    parent_comment_id    BIGINT      NULL     COMMENT '대댓글이면 부모(1단계까지)',
    author_type          VARCHAR(10) NOT NULL COMMENT 'CUSTOMER/AI',
    customer_id          BIGINT      NULL,
    content              TEXT        NOT NULL,
    sources_json         TEXT        NULL     COMMENT 'AI 답글 근거 목록',
    status               VARCHAR(10) NOT NULL DEFAULT 'VISIBLE',
    created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (community_comment_id),
    CONSTRAINT chk_cc_author CHECK ((author_type = 'CUSTOMER' AND customer_id IS NOT NULL) OR (author_type = 'AI' AND customer_id IS NULL)),
    CONSTRAINT chk_cc_status CHECK (status IN ('VISIBLE','HIDDEN','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='채팅방 댓글';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE community_post_reaction (
    community_post_id BIGINT      NOT NULL,
    customer_id       BIGINT      NOT NULL,
    reaction_type     VARCHAR(10) NOT NULL COMMENT 'LIKE/SCRAP',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (community_post_id, customer_id, reaction_type),
    CONSTRAINT chk_cpr_type CHECK (reaction_type IN ('LIKE','SCRAP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='글 공감·스크랩';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE community_comment_like (
    community_comment_id BIGINT   NOT NULL,
    customer_id          BIGINT   NOT NULL,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (community_comment_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='댓글 공감';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE community_report (
    community_report_id  BIGINT      NOT NULL AUTO_INCREMENT,
    community_post_id    BIGINT      NULL,
    community_comment_id BIGINT      NULL,
    reporter_id          BIGINT      NOT NULL,
    reason               VARCHAR(20) NOT NULL COMMENT 'ABUSE/SPAM/PRIVACY/ETC',
    status               VARCHAR(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    processed_by         BIGINT      NULL,
    processed_at         DATETIME    NULL,
    created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (community_report_id),
    UNIQUE KEY uk_cr_post    (reporter_id, community_post_id),
    UNIQUE KEY uk_cr_comment (reporter_id, community_comment_id),
    CONSTRAINT chk_cr_target CHECK ((community_post_id IS NULL) <> (community_comment_id IS NULL)),
    CONSTRAINT chk_cr_reason CHECK (reason IN ('ABUSE','SPAM','PRIVACY','ETC')),
    CONSTRAINT chk_cr_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='채팅방 신고';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE rag_chunk (
    rag_chunk_id    BIGINT       NOT NULL AUTO_INCREMENT,
    doc_name        VARCHAR(100) NOT NULL,
    doc_url         VARCHAR(500) NULL,
    section         VARCHAR(100) NOT NULL,
    content         TEXT         NOT NULL,
    embedding       MEDIUMBLOB   NULL,
    embedding_model VARCHAR(50)  NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rag_chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='제도 원문 조각(RAG)';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE customer_consent (
    customer_consent_id BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id         BIGINT      NOT NULL,
    consent_type        VARCHAR(30) NOT NULL,
    agreed              BOOLEAN     NOT NULL,
    changed_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_consent_id),
    CONSTRAINT chk_ccs_type CHECK (consent_type IN ('MARKETING_SMS','MARKETING_EMAIL','MARKETING_PUSH','NOTIFY_SETTLEMENT','NOTIFY_COMMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='수신동의·알림 설정 이력';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE investor_assessment (
    investor_assessment_id BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id            BIGINT      NOT NULL,
    total_score            INT         NOT NULL COMMENT '설문 총점',
    investor_type          VARCHAR(20) NOT NULL COMMENT '진단 결과 성향',
    answers_json           TEXT        NOT NULL COMMENT '문항별 응답',
    assessed_at            DATETIME    NOT NULL COMMENT '진단 시각(업무시계, 24개월 유효)',
    recorded_at            DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '실제 시각',
    PRIMARY KEY (investor_assessment_id),
    CONSTRAINT chk_ia_score CHECK (total_score >= 0),
    CONSTRAINT chk_ia_type  CHECK (investor_type IN ('STABLE','CONSERVATIVE','NEUTRAL','ACTIVE','AGGRESSIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='투자성향 진단 이력';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관. 시각은 업무시계가 아니라 실제 시각.
CREATE TABLE customer_login_history (
    customer_login_history_id BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id               BIGINT       NULL     COMMENT '실패했고 누구인지 모르면 NULL',
    succeeded                 BOOLEAN      NOT NULL,
    fail_reason               VARCHAR(30)  NULL     COMMENT '실패 사유 코드(성공이면 NULL). 시도한 아이디는 저장하지 않는다',
    ip_address                VARCHAR(45)  NULL,
    user_agent                VARCHAR(300) NULL     COMMENT '기기·브라우저',
    attempted_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '시도 시각(실제)',
    PRIMARY KEY (customer_login_history_id),
    CONSTRAINT chk_clh_reason CHECK (succeeded = (fail_reason IS NULL)),
    CONSTRAINT chk_clh_known  CHECK (succeeded = 0 OR customer_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='고객 로그인 이력';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE document_issue (
    document_issue_id BIGINT       NOT NULL AUTO_INCREMENT,
    account_id        BIGINT       NOT NULL,
    calc_id           BIGINT       NOT NULL COMMENT '근거 세액(tax_calculation)',
    doc_type          VARCHAR(30)  NOT NULL COMMENT 'TAX_RELIEF_CERT',
    issue_no          VARCHAR(30)  NOT NULL COMMENT '발급번호',
    status            VARCHAR(10)  NOT NULL COMMENT 'ISSUED/REVOKED',
    file_content      MEDIUMBLOB   NOT NULL COMMENT '발급한 PDF 파일',
    file_hash         CHAR(64)     NOT NULL COMMENT 'PDF SHA-256',
    issued_by_type    VARCHAR(10)  NOT NULL COMMENT 'CUSTOMER/ADMIN',
    issued_by_id      BIGINT       NOT NULL COMMENT 'customer_id 또는 admin_id',
    issued_at         DATETIME     NOT NULL COMMENT '발급 시각(업무시계)',
    revoked_at        DATETIME     NULL     COMMENT '취소 시각',
    recorded_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '실제 시각',
    PRIMARY KEY (document_issue_id),
    UNIQUE KEY uk_document_issue_no (issue_no),
    CONSTRAINT chk_di_type   CHECK (doc_type IN ('TAX_RELIEF_CERT')),
    CONSTRAINT chk_di_by     CHECK (issued_by_type IN ('CUSTOMER','ADMIN')),
    CONSTRAINT chk_di_status CHECK ((status = 'ISSUED' AND revoked_at IS NULL) OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='증빙서류 발급 기록';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE fund_flow_monthly (
    fund_flow_monthly_id BIGINT        NOT NULL AUTO_INCREMENT,
    account_id           BIGINT        NOT NULL,
    flow_month           DATE          NOT NULL COMMENT '집계 월(1일)',
    foreign_sell_amount  DECIMAL(15,0) NOT NULL DEFAULT 0 COMMENT '해외주식 매도(원화 환산)',
    krw_inflow_amount    DECIMAL(15,0) NOT NULL DEFAULT 0 COMMENT '원화 납입(확정산)',
    domestic_buy_amount  DECIMAL(15,0) NOT NULL DEFAULT 0 COMMENT '국내 매수',
    domestic_sell_amount DECIMAL(15,0) NOT NULL DEFAULT 0 COMMENT '국내 매도',
    withdrawal_amount    DECIMAL(15,0) NOT NULL DEFAULT 0 COMMENT '인출',
    calculated_at        DATETIME      NOT NULL COMMENT '집계 시각',
    run_id               VARCHAR(50)   NOT NULL COMMENT '배치 실행 id',
    PRIMARY KEY (fund_flow_monthly_id),
    UNIQUE KEY uk_ffm_account_month (account_id, flow_month),
    CONSTRAINT chk_ffm_month CHECK (DAYOFMONTH(flow_month) = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='자금 환류 월 집계';

-- [NO-SEED] 2차 신규(서비스가 생성). 생성기 무관.
CREATE TABLE notification (
    notification_id   BIGINT       NOT NULL AUTO_INCREMENT,
    customer_id       BIGINT       NOT NULL,
    notification_type VARCHAR(30)  NOT NULL,
    title             VARCHAR(100) NOT NULL,
    content           VARCHAR(500) NOT NULL,
    related_type      VARCHAR(30)  NOT NULL COMMENT '알림을 일으킨 사건 행의 테이블',
    related_id        BIGINT       NOT NULL COMMENT '사건 행 id(FK 없음)',
    read_at           DATETIME     NULL     COMMENT '읽은 시각',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notification_id),
    UNIQUE KEY uk_nt_event (customer_id, notification_type, related_type, related_id),
    CONSTRAINT chk_nt_type    CHECK (notification_type IN ('SETTLEMENT_FINALIZED','DOMESTIC_ORDER','ACCOUNT_STATUS','WITHDRAWAL','DOCUMENT_ISSUED','COMMUNITY_COMMENT','KIRU_REPLY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='고객 알림';

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

-- 2차 신규 테이블 FK
ALTER TABLE community_post          ADD CONSTRAINT fk_cp__customer        FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE community_comment       ADD CONSTRAINT fk_cc__post            FOREIGN KEY (community_post_id)    REFERENCES community_post (community_post_id);
ALTER TABLE community_comment       ADD CONSTRAINT fk_cc__parent          FOREIGN KEY (parent_comment_id)    REFERENCES community_comment (community_comment_id);
ALTER TABLE community_comment       ADD CONSTRAINT fk_cc__customer        FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE community_post_reaction ADD CONSTRAINT fk_cpr__post           FOREIGN KEY (community_post_id)    REFERENCES community_post (community_post_id);
ALTER TABLE community_post_reaction ADD CONSTRAINT fk_cpr__customer       FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE community_comment_like  ADD CONSTRAINT fk_ccl__comment        FOREIGN KEY (community_comment_id) REFERENCES community_comment (community_comment_id);
ALTER TABLE community_comment_like  ADD CONSTRAINT fk_ccl__customer       FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE community_report        ADD CONSTRAINT fk_cr__post            FOREIGN KEY (community_post_id)    REFERENCES community_post (community_post_id);
ALTER TABLE community_report        ADD CONSTRAINT fk_cr__comment         FOREIGN KEY (community_comment_id) REFERENCES community_comment (community_comment_id);
ALTER TABLE community_report        ADD CONSTRAINT fk_cr__reporter        FOREIGN KEY (reporter_id)          REFERENCES customer (customer_id);
ALTER TABLE community_report        ADD CONSTRAINT fk_cr__admin           FOREIGN KEY (processed_by)         REFERENCES admin_user (admin_id);
ALTER TABLE customer_consent        ADD CONSTRAINT fk_ccs__customer       FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE investor_assessment     ADD CONSTRAINT fk_ia__customer        FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE customer_login_history  ADD CONSTRAINT fk_clh__customer       FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);
ALTER TABLE document_issue          ADD CONSTRAINT fk_di__account         FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE document_issue          ADD CONSTRAINT fk_di__tax_calculation FOREIGN KEY (calc_id)              REFERENCES tax_calculation (calc_id);
ALTER TABLE fund_flow_monthly       ADD CONSTRAINT fk_ffm__account        FOREIGN KEY (account_id)           REFERENCES account (account_id);
ALTER TABLE notification            ADD CONSTRAINT fk_nt__customer        FOREIGN KEY (customer_id)          REFERENCES customer (customer_id);

ALTER TABLE domestic_order         ADD CONSTRAINT fk_do__account            FOREIGN KEY (account_id)          REFERENCES account (account_id);
ALTER TABLE domestic_order         ADD CONSTRAINT fk_do__domestic_product   FOREIGN KEY (domestic_product_id) REFERENCES domestic_product (domestic_product_id);
ALTER TABLE domestic_order         ADD CONSTRAINT fk_do__stock_package      FOREIGN KEY (stock_package_id)    REFERENCES stock_package (stock_package_id);
ALTER TABLE stock_package          ADD CONSTRAINT fk_sp__customer           FOREIGN KEY (customer_id)         REFERENCES customer (customer_id);
ALTER TABLE stock_package_item     ADD CONSTRAINT fk_spi__stock_package     FOREIGN KEY (stock_package_id)    REFERENCES stock_package (stock_package_id);
ALTER TABLE stock_package_item     ADD CONSTRAINT fk_spi__domestic_product  FOREIGN KEY (domestic_product_id) REFERENCES domestic_product (domestic_product_id);
ALTER TABLE domestic_product_theme ADD CONSTRAINT fk_dpt__domestic_product  FOREIGN KEY (domestic_product_id) REFERENCES domestic_product (domestic_product_id);
ALTER TABLE domestic_product_theme ADD CONSTRAINT fk_dpt__stock_theme       FOREIGN KEY (stock_theme_id)      REFERENCES stock_theme (stock_theme_id);
ALTER TABLE stock_risk_grade       ADD CONSTRAINT fk_srg__domestic_product  FOREIGN KEY (domestic_product_id) REFERENCES domestic_product (domestic_product_id);
ALTER TABLE stock_recommendation   ADD CONSTRAINT fk_sr__customer           FOREIGN KEY (customer_id)         REFERENCES customer (customer_id);
ALTER TABLE stock_recommendation   ADD CONSTRAINT fk_sr__domestic_product   FOREIGN KEY (domestic_product_id) REFERENCES domestic_product (domestic_product_id);
