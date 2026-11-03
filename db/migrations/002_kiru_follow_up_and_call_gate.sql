-- Manual maintenance migration. Back up and verify on a disposable DB first.
-- Not auto-run by the application. Do not run 01_ria_ddl.sql on an existing DB.

-- 1) 끼루 후속 질문 상태(FOLLOW_UP) 추가
ALTER TABLE community_comment DROP CONSTRAINT chk_cc_kiru;
ALTER TABLE community_comment
    ADD CONSTRAINT chk_cc_kiru CHECK (kiru_status IS NULL OR kiru_status IN
        ('PENDING','ANSWERED','NO_SOURCE','OUT_OF_SCOPE','FOLLOW_UP','FAILED'));

-- 기존 후속 질문(OUT_OF_SCOPE로 저장됨)을 옮긴다. AI 댓글에 @끼루로 답한 댓글은 항상 후속 질문이라 이 조건이 정확하다.
UPDATE community_comment c
    JOIN community_comment p ON p.community_comment_id = c.parent_comment_id
SET c.kiru_status = 'FOLLOW_UP'
WHERE c.kiru_status = 'OUT_OF_SCOPE' AND p.author_type = 'AI';

-- 2) 끼루 전체 하루 상한 잠금 행
CREATE TABLE IF NOT EXISTS kiru_call_gate (
    gate_id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_kcg_singleton CHECK (gate_id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='끼루 전체 하루 상한 잠금 행';
INSERT IGNORE INTO kiru_call_gate(gate_id) VALUES (1);
