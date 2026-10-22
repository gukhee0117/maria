-- 기존 ria_admin DB용 수동 마이그레이션. 앱 시작 시 자동 실행되지 않는다.
-- 01_ria_ddl.sql은 초기화용 DROP 구문을 포함하므로 기존 DB에 재실행하지 않는다.
-- 실행 전 별도 승인·백업 및 대상 DB 확인이 필요하다. 이 파일 작성만으로 DB가 변경되지는 않는다.
USE ria_admin;

ALTER TABLE inbound_detail
    ADD COLUMN IF NOT EXISTS registrable_stock_id BIGINT NULL
        COMMENT '증권사 적격 원본 ID(외부 참조, FK 없음). 기존 데이터는 NULL 허용'
        AFTER foreign_product_id;

-- 실제 상호배타 CHECK는 없으므로 제약 해제가 아니라 의미를 바로잡는 주석 변경이다.
ALTER TABLE inbound_detail
    MODIFY COLUMN source_broker VARCHAR(20) NULL
        COMMENT '최초 타사 출처 증권사명. 당사 출처 일반계좌와 함께 보존 가능',
    MODIFY COLUMN source_general_account_id BIGINT NULL
        COMMENT '이번 RIA 입고의 출처 당사 일반계좌 ID(외부 참조, FK 없음). 배당 비율배분 기준';

-- 기존 행의 원본 ID를 추정해서 채우지 않는다. FK·UNIQUE·NOT NULL도 추가하지 않는다.
-- 실제 현재 잔고·재입고·출고 예약 관련 변경은 이슈 2의 별도 마이그레이션 대상이다.
