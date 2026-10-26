-- =====================================================================
-- 2차 확정 ERD 반영용 ALTER 스크립트 (이슈 #308)
-- 이미 1차 DDL(01_ria_ddl.sql)로 만들어져 데이터가 들어 있는 DB에 적용한다. 데이터는 지우지 않는다.
-- 새로 만드는 DB는 이 파일 대신 01_ria_ddl.sql을 쓴다(둘의 결과 스키마는 같다).
-- 한 번만 실행한다(다시 실행하면 이미 있는 컬럼·테이블에서 오류). 순서를 바꾸지 않는다.
-- 근거: docs/phase2-erd-changes-2026-10-22.md §3, 10-25 변경, §6
-- =====================================================================
USE ria_admin;

-- 1) customer: 닉네임·이메일, 투자성향 UNASSIGNED
ALTER TABLE customer
    ADD COLUMN nickname VARCHAR(20)  NULL COMMENT '채팅방 닉네임' AFTER name,
    ADD COLUMN email    VARCHAR(100) NULL COMMENT '이메일' AFTER phone,
    ADD UNIQUE KEY uk_customer_nickname (nickname);
ALTER TABLE customer DROP CONSTRAINT chk_customer_investor_type;
ALTER TABLE customer ADD CONSTRAINT chk_customer_investor_type
    CHECK (investor_type IN ('STABLE','CONSERVATIVE','NEUTRAL','ACTIVE','AGGRESSIVE','UNASSIGNED'));

-- 2) customer_auth: 같은 (type, login_id) 중복 가입 방지
ALTER TABLE customer_auth ADD UNIQUE KEY uk_customer_auth_type_login (type, login_id);

-- 3) domestic_product: OpenDART 회사 고유번호
ALTER TABLE domestic_product
    ADD COLUMN dart_corp_code CHAR(8) NULL COMMENT 'OpenDART 회사 고유번호(STOCK만). 끼루 종목 정보 답글용' AFTER ticker;

-- 4) audit_log: 행위자 종류. 기존 행은 DEFAULT로 ADMIN이 되고 admin_id도 그대로다.
ALTER TABLE audit_log
    MODIFY COLUMN admin_id BIGINT NULL COMMENT '관리자 행위일 때만. 새 코드는 actor_type+actor_id',
    ADD COLUMN actor_type VARCHAR(10) NOT NULL DEFAULT 'ADMIN' COMMENT 'ADMIN/CUSTOMER/SYSTEM' AFTER audit_id,
    ADD COLUMN actor_id   BIGINT      NULL COMMENT 'CUSTOMER면 customer_id, SYSTEM이면 NULL' AFTER actor_type,
    ADD CONSTRAINT chk_audit_actor_type CHECK (actor_type IN ('ADMIN','CUSTOMER','SYSTEM'));

-- 5) tax_rule: 가입기한 규칙
ALTER TABLE tax_rule DROP CONSTRAINT chk_tax_rule_type;
ALTER TABLE tax_rule ADD CONSTRAINT chk_tax_rule_type
    CHECK (rule_type IN ('RELIEF_RATE','DEPOSIT_LIMIT','HOLDING_PERIOD','BASIC_DEDUCTION','TAX_RATE','ENROLLMENT_OPEN'));
ALTER TABLE tax_rule MODIFY COLUMN rule_type VARCHAR(20) NOT NULL
    COMMENT 'RELIEF_RATE/DEPOSIT_LIMIT/HOLDING_PERIOD/BASIC_DEDUCTION/TAX_RATE/ENROLLMENT_OPEN';

-- 6) account: 쓰지 않는 컬럼 제거
ALTER TABLE account DROP COLUMN last_domestic_trade_id;

-- 7) 신규 19개 테이블과 FK
-- ---------------------------------------------------------------------
-- 2차 신규 테이블 (maria 확정 ERD, 이슈 #308)
-- ---------------------------------------------------------------------
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

CREATE TABLE stock_theme (
    stock_theme_id BIGINT      NOT NULL AUTO_INCREMENT,
    name           VARCHAR(30) NOT NULL,
    PRIMARY KEY (stock_theme_id),
    UNIQUE KEY uk_stock_theme_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='테마(자체 분류)';

CREATE TABLE domestic_product_theme (
    domestic_product_id BIGINT NOT NULL,
    stock_theme_id      BIGINT NOT NULL,
    PRIMARY KEY (domestic_product_id, stock_theme_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='종목-테마 연결';

CREATE TABLE stock_risk_grade (
    domestic_product_id BIGINT       NOT NULL,
    var_975             DECIMAL(7,4) NOT NULL,
    risk_grade          TINYINT      NOT NULL,
    volatility          DECIMAL(7,4) NOT NULL,
    price_from          DATE         NOT NULL,
    price_to            DATE         NOT NULL,
    calculated_at       DATETIME     NOT NULL COMMENT 'Python 계산 시각',
    synced_at           DATETIME     NOT NULL COMMENT 'maria 저장 시각',
    PRIMARY KEY (domestic_product_id),
    CONSTRAINT chk_srg_grade CHECK (risk_grade BETWEEN 1 AND 6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='종목 위험등급(Python risk 사본)';

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

CREATE TABLE news_item (
    news_item_id  BIGINT        NOT NULL AUTO_INCREMENT,
    ticker        VARCHAR(20)   NOT NULL,
    title         VARCHAR(300)  NOT NULL,
    description   VARCHAR(1000) NOT NULL COMMENT '발췌문(본문 저장 안 함)',
    link          VARCHAR(500)  NOT NULL,
    original_link VARCHAR(500)  NULL,
    published_at  DATETIME      NOT NULL,
    related_count INT           NOT NULL DEFAULT 1 COMMENT '같은 사건 기사 수(이슈 점수)',
    picked_date   DATE          NULL     COMMENT '대표 기사로 뽑힌 날',
    summary       VARCHAR(1000) NULL     COMMENT '검증 통과한 AI 요약',
    summary_model VARCHAR(50)   NULL,
    collected_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (news_item_id),
    UNIQUE KEY uk_news_item_link (link),
    UNIQUE KEY uk_news_item_pick (ticker, picked_date),
    CONSTRAINT chk_ni_related CHECK (related_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='보유 종목 뉴스·기사 요약';

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

CREATE TABLE community_post_reaction (
    community_post_id BIGINT      NOT NULL,
    customer_id       BIGINT      NOT NULL,
    reaction_type     VARCHAR(10) NOT NULL COMMENT 'LIKE/SCRAP',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (community_post_id, customer_id, reaction_type),
    CONSTRAINT chk_cpr_type CHECK (reaction_type IN ('LIKE','SCRAP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='글 공감·스크랩';

CREATE TABLE community_comment_like (
    community_comment_id BIGINT   NOT NULL,
    customer_id          BIGINT   NOT NULL,
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (community_comment_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='댓글 공감';

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

CREATE TABLE customer_consent (
    customer_consent_id BIGINT      NOT NULL AUTO_INCREMENT,
    customer_id         BIGINT      NOT NULL,
    consent_type        VARCHAR(30) NOT NULL,
    agreed              BOOLEAN     NOT NULL,
    changed_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (customer_consent_id),
    CONSTRAINT chk_ccs_type CHECK (consent_type IN ('MARKETING_SMS','MARKETING_EMAIL','MARKETING_PUSH','NOTIFY_SETTLEMENT','NOTIFY_COMMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='수신동의·알림 설정 이력';

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
