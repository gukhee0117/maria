-- ============================================================================
-- MARIA 라이브 시연 — 선행 데이터 시딩
-- ============================================================================
-- 목적: db/seed/*.sql(customer_seed, mydata_seed, securities_seed)는 이미
--       account_id=1..18000(ACCOUNT_COUNT)에 계좌를 다 만들어놓은 상태라,
--       "계좌 신청부터" 보여주는 라이브 데모엔 못 씀 (이미 OPENED라 신청 단계가 없음).
--       registrable_stock(보유종목)도 그 1..18000 중 OPENED인 사람한테만 있어서,
--       "계좌 없고 종목만 있는" 깨끗한 고객이 벌크시드엔 존재하지 않음.
--
--       그래서 ACCOUNT_COUNT(18000) 다음 번호인 customer_id=18001을 데모 전용으로
--       고정해서 쓴다. 이미 customer/general_customer/general_account는 population
--       생성 파이프라인(§12: population→customer→general_customer→general_account)에서
--       3만 명 전체에 대해 만들어져 있으므로, registrable_stock 한 줄만 얹으면 됨.
--
-- 실행 순서: 0-1(조회, RIA DB) → 0-2(조회, 증권사 DB) → 0-3(INSERT, 증권사 DB)
-- ============================================================================

-- --------------------------------------------------------------------------
-- 0-1. RIA DB(ria_admin) — 데모 고객의 ci_hash 확인
-- --------------------------------------------------------------------------
-- USE ria_admin;
SELECT customer_id, name, ci_hash
FROM customer
WHERE customer_id = 18001;
-- account 없는 상태인지도 같이 확인 (0행이어야 정상)
SELECT * FROM account WHERE customer_id = 18001;


-- --------------------------------------------------------------------------
-- 0-2. 증권사 DB(return-securities) — 그 ci_hash의 general_account_id 확인
-- --------------------------------------------------------------------------
-- USE securities;
SELECT ga.general_account_id, ga.account_no, ga.account_type, ga.status
FROM general_account ga
JOIN general_customer gc ON gc.general_customer_id = ga.general_customer_id
WHERE gc.ci_hash = '{0-1에서 나온 ci_hash}';
-- 여러 개면 아무거나(첫 번째) 써도 됨 — 입고 시나리오엔 계좌 유형 안 가림


-- --------------------------------------------------------------------------
-- 0-3. 증권사 DB(return-securities) — 2025-12-23 기준 보유수량 심기
-- --------------------------------------------------------------------------
-- foreign_product_id=1(AAPL, customer_seed.sql 기준)을 그대로 사용.
-- held_qty=300으로 넉넉히 잡아서, 이후 3번의 매도(40+40+40)를 다 감당하게 함.
INSERT INTO registrable_stock
    (general_account_id, foreign_product_id, held_qty, source_broker,
     recorded_at, purchase_date, purchase_price, purchase_currency, purchase_fx_rate)
VALUES
    ({0-2에서 나온 general_account_id}, 1, 300.0000, NULL,
     '2025-12-23 08:00:00', '2024-01-15', 100.0000, 'USD', 1350.0000);


-- ============================================================================
-- 확장 시나리오 B1용 — 합산 한도 초과 준비 (선택)
-- ============================================================================
-- §2 핵심규칙1: "전 증권사 합산 매도한도 5,000만원"을 시연하려면, 같은 ci_hash로
-- 타사(myData)에 이미 큰 설정한도가 잡혀 있는 상태를 만들어야 함.
-- myData DB(mydata)에 실행.
-- --------------------------------------------------------------------------
-- USE mydata;
INSERT INTO mydata_ria_account (ci_hash, broker_name, ria_limit, ria_cumulative_sell)
VALUES ('{0-1에서 나온 ci_hash}', '타사증권', 45000000.00, 0.00);
-- 이후 우리 쪽에서 customer_id=18001로 계좌 신청 시 limitAmount를 1000만원 이상으로
-- 넣으면 (45,000,000 + limitAmount) > 50,000,000 이 되어 신청 자체가 막히는 걸 보여줌.
-- (POST /api/account/applications 가 validateLimitAvailability에서 즉시 거부 —
--  "개설 후 사후 초과 감지" 경로는 별도 배치 확인 안 됨, README 참고)


-- ============================================================================
-- 확장 시나리오 G1용 — 외부 순매수 감시 대상 주입
-- ============================================================================
-- myData DB(mydata)에 실행. demo-scenario.http 7단계 직전에 실행.
-- --------------------------------------------------------------------------
-- USE mydata;
INSERT INTO mydata_trade
    (ci_hash, broker_name, trade_type, stock_type, ticker, qty, trade_date, amount)
VALUES
    ('{0-1에서 나온 ci_hash}', '타사증권', 'BUY', 'FOREIGN_STOCK', 'AAPL', 50, '2026-06-15', 20000000.00);
