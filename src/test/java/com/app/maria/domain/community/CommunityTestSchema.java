package com.app.maria.domain.community;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 커뮤니티 테스트가 쓰는 H2 테이블. 운영 DDL(maria/db/01_ria_ddl.sql)과 같은 컬럼·CHECK·UNIQUE를 갖되, 같은 H2를 다른 테스트와
 * 공유하므로 FK는 걸지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CommunityTestSchema {

    public static void create(JdbcTemplate jdbc) {
        drop(jdbc);
        jdbc.execute(
                """
                CREATE TABLE customer (
                    customer_id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL,
                    birth_date DATE NOT NULL, phone VARCHAR(20), email VARCHAR(100), investor_type VARCHAR(20) NOT NULL,
                    ci_hash VARCHAR(64) NOT NULL UNIQUE,
                    nickname VARCHAR(20) UNIQUE,
                    investor_type_public BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE community_post (
                    community_post_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    ticker VARCHAR(20), customer_id BIGINT NOT NULL,
                    content VARCHAR(4000) NOT NULL, kiru_status VARCHAR(15),
                    kiru_retry_count INT NOT NULL DEFAULT 0,
                    status VARCHAR(10) NOT NULL DEFAULT 'VISIBLE',
                    created_at TIMESTAMP NOT NULL, edited_at TIMESTAMP,
                    CONSTRAINT chk_cp_status CHECK (status IN ('VISIBLE','HIDDEN','DELETED')),
                    CONSTRAINT chk_cp_kiru CHECK (kiru_status IS NULL OR kiru_status IN
                        ('PENDING','ANSWERED','NO_SOURCE','OUT_OF_SCOPE','FAILED'))
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE community_comment (
                    community_comment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    community_post_id BIGINT NOT NULL, parent_comment_id BIGINT,
                    author_type VARCHAR(10) NOT NULL, customer_id BIGINT,
                    content VARCHAR(4000) NOT NULL, sources_json VARCHAR(4000),
                    kiru_status VARCHAR(15),
                    kiru_retry_count INT NOT NULL DEFAULT 0,
                    status VARCHAR(10) NOT NULL DEFAULT 'VISIBLE',
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT chk_cc_author CHECK ((author_type = 'CUSTOMER' AND customer_id IS NOT NULL)
                        OR (author_type = 'AI' AND customer_id IS NULL)),
                    CONSTRAINT chk_cc_status CHECK (status IN ('VISIBLE','HIDDEN','DELETED')),
                    CONSTRAINT chk_cc_kiru CHECK (kiru_status IS NULL OR kiru_status IN
                        ('PENDING','ANSWERED','NO_SOURCE','OUT_OF_SCOPE','FOLLOW_UP','FAILED'))
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE kiru_call_gate (
                    gate_id TINYINT NOT NULL PRIMARY KEY,
                    CONSTRAINT chk_kcg_singleton CHECK (gate_id = 1)
                )
                """);
        jdbc.execute("INSERT INTO kiru_call_gate (gate_id) VALUES (1)");
        jdbc.execute(
                """
                CREATE TABLE kiru_retry_log (
                    kiru_retry_log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_id BIGINT NOT NULL, retried_at TIMESTAMP NOT NULL
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE community_post_reaction (
                    community_post_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
                    reaction_type VARCHAR(10) NOT NULL, created_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (community_post_id, customer_id, reaction_type),
                    CONSTRAINT chk_cpr_type CHECK (reaction_type IN ('LIKE','SCRAP'))
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE community_comment_like (
                    community_comment_id BIGINT NOT NULL, customer_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (community_comment_id, customer_id)
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE community_report (
                    community_report_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    community_post_id BIGINT, community_comment_id BIGINT,
                    reporter_id BIGINT NOT NULL, reason VARCHAR(20) NOT NULL,
                    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
                    processed_by BIGINT, processed_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL,
                    CONSTRAINT uk_cr_post UNIQUE (reporter_id, community_post_id),
                    CONSTRAINT uk_cr_comment UNIQUE (reporter_id, community_comment_id),
                    CONSTRAINT chk_cr_target CHECK ((community_post_id IS NULL) <> (community_comment_id IS NULL)),
                    CONSTRAINT chk_cr_reason CHECK (reason IN ('ABUSE','SPAM','PRIVACY','ETC')),
                    CONSTRAINT chk_cr_status CHECK (status IN ('PENDING','ACCEPTED','REJECTED'))
                )
                """);
    }

    public static void drop(JdbcTemplate jdbc) {
        jdbc.execute("DROP TABLE IF EXISTS community_report");
        jdbc.execute("DROP TABLE IF EXISTS community_comment_like");
        jdbc.execute("DROP TABLE IF EXISTS community_post_reaction");
        jdbc.execute("DROP TABLE IF EXISTS kiru_retry_log");
        jdbc.execute("DROP TABLE IF EXISTS kiru_call_gate");
        jdbc.execute("DROP TABLE IF EXISTS community_comment");
        jdbc.execute("DROP TABLE IF EXISTS community_post");
        jdbc.execute("DROP TABLE IF EXISTS customer");
    }
}
