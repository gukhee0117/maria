package com.app.maria.global.audit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.dto.AuditLogSearchDTO;
import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditLogMapperTest {

    private static PooledDataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession sqlSession;
    private AuditLogMapper auditLogMapper;

    @BeforeAll
    static void configureMyBatis() throws IOException {
        try (Reader reader = Resources.getResourceAsReader("mybatis-audit-test-config.xml")) {
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
        }
        dataSource =
                (PooledDataSource)
                        sqlSessionFactory.getConfiguration().getEnvironment().getDataSource();
    }

    @BeforeEach
    void setUpDatabase() throws SQLException {
        resetSchema();
        sqlSession = sqlSessionFactory.openSession(true);
        auditLogMapper = sqlSession.getMapper(AuditLogMapper.class);
    }

    @AfterEach
    void closeSession() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) {
            dataSource.forceCloseAll();
        }
    }

    private AuditLogDTO auditLog(
            Long adminId, String targetTable, String targetPk, String reasonCode) {
        return AuditLogDTO.builder()
                .adminId(adminId)
                .targetTable(targetTable)
                .targetPk(targetPk)
                .beforeValue("VIEWER")
                .afterValue("ADMIN")
                .reasonCode(reasonCode)
                .build();
    }

    private AuditLogSearchDTO.AuditLogSearchDTOBuilder searchDefaults() {
        return AuditLogSearchDTO.builder().size(20).offset(0);
    }

    @Test
    @DisplayName("insertLog로 저장하면 1건이 반영된다")
    void insertLogAffectsOneRow() {
        int affected =
                auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));

        assertThat(affected).isEqualTo(1);
    }

    @Test
    @DisplayName("지정한 업무시각으로 감사로그를 저장한다")
    void insertLogStoresProvidedProcessedAt() {
        LocalDateTime businessTime = LocalDateTime.of(2026, 8, 12, 10, 30);
        AuditLogDTO auditLog = auditLog(1L, "ACCOUNT", "100", "ACCOUNT_CLOSURE_APPROVED");
        auditLog.setProcessedAt(businessTime);

        auditLogMapper.insertLog(auditLog);

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());
        assertThat(result)
                .singleElement()
                .extracting(AuditLogDTO::getProcessedAt)
                .isEqualTo(businessTime);
    }

    @Test
    @DisplayName("검색조건이 전부 비어 있으면 전체 목록을 최신순으로 반환한다")
    void selectAuditLogsReturnsAllOrderedByProcessedAtDescWhenNoFilters() throws SQLException {
        insertLogAt(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE", "2026-08-01 09:00:00");
        insertLogAt(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED", "2026-08-05 09:00:00");

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTargetTable()).isEqualTo("SELL_ORDER");
        assertThat(result.get(1).getTargetTable()).isEqualTo("ADMIN_USER");
    }

    @Test
    @DisplayName("targetTable로 정확히 일치하는 작업유형만 필터링한다")
    void selectAuditLogsFiltersByExactTargetTable() {
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(searchDefaults().targetTable("SELL_ORDER").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetTable()).isEqualTo("SELL_ORDER");
    }

    @Test
    @DisplayName("adminKeyword가 수행자(actor) 이름에 포함되면 매치된다")
    void selectAuditLogsFiltersByAdminKeywordMatchingActorName() throws SQLException {
        insertAdmin(1L, "박지훈", "REVIEWER");
        insertAdmin(2L, "최동수", "SETTLEMENT");
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "9", "ADMIN_ROLE_UPDATE"));
        auditLogMapper.insertLog(auditLog(2L, "ADMIN_USER", "9", "ADMIN_ROLE_UPDATE"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(searchDefaults().adminKeyword("박지훈").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAdminId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("adminKeyword가 수행자 역할 한글라벨에 매치되는 코드 목록(matchedRoles)에 걸리면 조회된다")
    void selectAuditLogsFiltersByAdminKeywordMatchingMatchedRoles() throws SQLException {
        insertAdmin(1L, "천유진", "ADMIN");
        insertAdmin(2L, "박지훈", "REVIEWER");
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "9", "ADMIN_ROLE_UPDATE"));
        auditLogMapper.insertLog(auditLog(2L, "ADMIN_USER", "9", "ADMIN_ROLE_UPDATE"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(
                        searchDefaults()
                                .adminKeyword("최고관리자")
                                .matchedRoles(List.of("ADMIN"))
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAdminId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("targetKeyword가 대상(target) 관리자 이름에 포함되면 매치된다 (actor와 다른 별도 join)")
    void selectAuditLogsFiltersByTargetKeywordMatchingTargetAdminName() throws SQLException {
        insertAdmin(1L, "박지훈", "REVIEWER");
        insertAdmin(2L, "이국희", "VIEWER");
        // 1번(박지훈)이 2번(이국희)의 권한을 변경한 로그: 수행자=1, 대상=targetPk 2
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(searchDefaults().targetKeyword("이국희").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetAdminName()).isEqualTo("이국희");
    }

    @Test
    @DisplayName("작업유형이 ACCOUNT면 account를 직접 join해서 계좌번호를 targetOwnerAccountNo로 조회한다")
    void selectAuditLogsResolvesAccountTargetOwnerAccountNo() throws SQLException {
        insertAccount(200L, "9000000001");
        auditLogMapper.insertLog(auditLog(1L, "ACCOUNT", "200", "ACCOUNT_OPENED"));

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetOwnerAccountNo()).isEqualTo("9000000001");
    }

    @Test
    @DisplayName("targetKeyword가 ACCOUNT 대상 계좌번호에 포함되면 매치된다 (account 직접 join)")
    void selectAuditLogsFiltersByTargetKeywordMatchingAccountOwnerAccountNo() throws SQLException {
        insertAccount(200L, "9000000001");
        auditLogMapper.insertLog(auditLog(1L, "ACCOUNT", "200", "ACCOUNT_OPENED"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(
                        searchDefaults().targetKeyword("9000000001").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetOwnerAccountNo()).isEqualTo("9000000001");
    }

    @Test
    @DisplayName("작업유형이 SETTLEMENT_BATCH면 settlement_batch를 직접 join해서 executed_at을 조회한다")
    void selectAuditLogsResolvesSettlementBatchExecutedAt() throws SQLException {
        insertSettlementBatch(300L, "2026-08-13 09:00:00");
        auditLogMapper.insertLog(
                auditLog(1L, "SETTLEMENT_BATCH", "300", "SETTLEMENT_BATCH_REQUESTED"));

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetBatchExecutedAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 13, 9, 0));
    }

    @Test
    @DisplayName("대응하는 settlement_batch가 없어도 조회는 되고, executed_at은 null이다")
    void selectAuditLogsLeavesTargetBatchExecutedAtNullWhenBatchMissing() {
        auditLogMapper.insertLog(
                auditLog(1L, "SETTLEMENT_BATCH", "999", "SETTLEMENT_BATCH_REQUESTED"));

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetBatchExecutedAt()).isNull();
    }

    @Test
    @DisplayName("작업유형이 SETTLEMENT_ITEM이면 settlement_batch join과 무관하게 정상 조회된다 (join 대상 아님)")
    void selectAuditLogsHandlesSettlementItemWithoutBatchJoin() {
        auditLogMapper.insertLog(auditLog(1L, "SETTLEMENT_ITEM", "46", "SETTLEMENT_ITEM_RETRIED"));

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetBatchExecutedAt()).isNull();
        assertThat(result.get(0).getTargetPk()).isEqualTo("46");
    }

    @Test
    @DisplayName("targetKeyword가 target_pk 원문에 포함되면 매치된다")
    void selectAuditLogsFiltersByTargetKeywordMatchingRawTargetPk() {
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "12345", "SELL_ORDER_EXECUTED"));
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "99999", "SELL_ORDER_EXECUTED"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(searchDefaults().targetKeyword("123").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetPk()).isEqualTo("12345");
    }

    @Test
    @DisplayName("reasonKeyword가 reason_code 원문(자유텍스트)에 포함되면 매치된다 - 시스템시각 사유 케이스")
    void selectAuditLogsFiltersByReasonKeywordMatchingRawText() {
        auditLogMapper.insertLog(auditLog(1L, "SYSTEM_CLOCK", "1", "1년 경과 시연을 위한 시각 조작"));
        auditLogMapper.insertLog(auditLog(1L, "SYSTEM_CLOCK", "1", "감면구간 테스트"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(searchDefaults().reasonKeyword("시연").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReasonCode()).isEqualTo("1년 경과 시연을 위한 시각 조작");
    }

    @Test
    @DisplayName("reasonKeyword가 사유 한글라벨에 매치되는 코드 목록(matchedReasonCodes)에 걸리면 조회된다")
    void selectAuditLogsFiltersByReasonKeywordMatchingMatchedReasonCodes() {
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED"));
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "11", "SELL_ORDER_REJECTED"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(
                        searchDefaults()
                                .reasonKeyword("반려")
                                .matchedReasonCodes(List.of("SELL_ORDER_REJECTED"))
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReasonCode()).isEqualTo("SELL_ORDER_REJECTED");
    }

    @Test
    @DisplayName(
            "knownReasonCodes에 있는(=한글 라벨이 있는) 코드는 원문 부분일치로 안 잡히고, 라벨 없는 자유텍스트만 원문으로 잡힌다"
                    + " (화면엔 라벨만 보이는데 원문으로 걸리면 화면과 검색이 안 맞는 문제 방지)")
    void selectAuditLogsExcludesKnownReasonCodesFromRawTextMatch() {
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));
        auditLogMapper.insertLog(auditLog(1L, "SYSTEM_CLOCK", "1", "test-debug"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(
                        searchDefaults()
                                .reasonKeyword("t")
                                .knownReasonCodes(List.of("ADMIN_ROLE_UPDATE"))
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getReasonCode()).isEqualTo("test-debug");
    }

    @Test
    @DisplayName("knownReasonCodes가 비어있으면(=null) 기존처럼 원문 매칭이 전부 적용된다")
    void selectAuditLogsAppliesRawTextMatchToAllCodesWhenKnownReasonCodesAbsent() {
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(searchDefaults().reasonKeyword("UPDATE").build());

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("기간(startDate~endDate)으로 필터링한다")
    void selectAuditLogsFiltersByDateRange() throws SQLException {
        insertLogAt(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE", "2026-01-01 09:00:00");
        insertLogAt(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED", "2026-08-05 09:00:00");
        insertLogAt(1L, "SELL_ORDER", "11", "SELL_ORDER_EXECUTED", "2026-12-31 09:00:00");

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(
                        searchDefaults()
                                .startDate(LocalDateTime.of(2026, 6, 1, 0, 0))
                                .endDate(LocalDateTime.of(2026, 9, 1, 0, 0))
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetPk()).isEqualTo("10");
    }

    @Test
    @DisplayName("targetTable/adminKeyword/reasonKeyword를 동시에 걸면 AND로 결합되어 전부 만족하는 행만 반환한다")
    void selectAuditLogsCombinesMultipleFieldsWithAnd() throws SQLException {
        insertAdmin(1L, "박지훈", "REVIEWER");
        insertAdmin(2L, "박지훈", "REVIEWER");
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED"));
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "11", "SELL_ORDER_REJECTED"));
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "3", "ADMIN_ROLE_UPDATE"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(
                        searchDefaults()
                                .targetTable("SELL_ORDER")
                                .adminKeyword("박지훈")
                                .reasonKeyword("EXECUTED")
                                .build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetPk()).isEqualTo("10");
    }

    @Test
    @DisplayName("조건에 맞는 행이 없으면 빈 리스트를 반환한다")
    void selectAuditLogsReturnsEmptyListWhenNoMatch() {
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED"));

        List<AuditLogDTO> result =
                auditLogMapper.selectAuditLogs(searchDefaults().adminKeyword("존재하지않는이름").build());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("admin_user와 join되어 수행자 이름/역할이 함께 조회된다")
    void selectAuditLogsJoinsAdminNameAndRole() throws SQLException {
        insertAdmin(1L, "박지훈", "REVIEWER");
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAdminName()).isEqualTo("박지훈");
        assertThat(result.get(0).getAdminRole()).isEqualTo("REVIEWER");
    }

    @Test
    @DisplayName("admin_user에 없는 관리자가 남긴 로그도 조회되고, 이름/역할은 null이다")
    void selectAuditLogsLeavesAdminNameNullWhenAdminUserMissing() {
        auditLogMapper.insertLog(auditLog(999L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAdminName()).isNull();
        assertThat(result.get(0).getAdminRole()).isNull();
    }

    @Test
    @DisplayName("작업유형이 SYSTEM_CLOCK이면 target 관련 join 결과 없이도 정상 조회된다")
    void selectAuditLogsHandlesSystemClockTargetWithoutTargetJoins() {
        auditLogMapper.insertLog(auditLog(1L, "SYSTEM_CLOCK", "1", "시연용 시각 조작"));

        List<AuditLogDTO> result = auditLogMapper.selectAuditLogs(searchDefaults().build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTargetAdminName()).isNull();
    }

    @Test
    @DisplayName("size/offset으로 페이지를 나눠 최신순으로 반환한다")
    void selectAuditLogsAppliesSizeAndOffset() throws SQLException {
        insertLogAt(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED", "2026-08-01 09:00:00");
        insertLogAt(1L, "SELL_ORDER", "11", "SELL_ORDER_EXECUTED", "2026-08-02 09:00:00");
        insertLogAt(1L, "SELL_ORDER", "12", "SELL_ORDER_EXECUTED", "2026-08-03 09:00:00");

        List<AuditLogDTO> firstPage =
                auditLogMapper.selectAuditLogs(
                        AuditLogSearchDTO.builder().size(1).offset(0).build());
        List<AuditLogDTO> secondPage =
                auditLogMapper.selectAuditLogs(
                        AuditLogSearchDTO.builder().size(1).offset(1).build());

        assertThat(firstPage).hasSize(1);
        assertThat(firstPage.get(0).getTargetPk()).isEqualTo("12");
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.get(0).getTargetPk()).isEqualTo("11");
    }

    @Test
    @DisplayName("countAuditLogs는 size/offset과 무관하게 조건에 맞는 전체 건수를 반환한다")
    void countAuditLogsReturnsTotalMatchingFilterRegardlessOfPaging() {
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "10", "SELL_ORDER_EXECUTED"));
        auditLogMapper.insertLog(auditLog(1L, "SELL_ORDER", "11", "SELL_ORDER_EXECUTED"));
        auditLogMapper.insertLog(auditLog(2L, "ADMIN_USER", "3", "ADMIN_ROLE_UPDATE"));

        long total =
                auditLogMapper.countAuditLogs(searchDefaults().targetTable("SELL_ORDER").build());
        List<AuditLogDTO> onePage =
                auditLogMapper.selectAuditLogs(
                        searchDefaults().targetTable("SELL_ORDER").size(1).offset(0).build());

        assertThat(total).isEqualTo(2);
        assertThat(onePage).hasSize(1);
    }

    @Test
    @DisplayName(
            "countAuditLogs도 adminKeyword/targetKeyword 조건에 필요한 join(admin_user×2, account)이 걸려있어 에러 없이 동작한다")
    void countAuditLogsWorksWithFieldFiltersRequiringAllJoins() throws SQLException {
        insertAdmin(1L, "박지훈", "REVIEWER");
        insertAdmin(2L, "이국희", "VIEWER");
        insertAccount(200L, "9000000001");
        auditLogMapper.insertLog(auditLog(1L, "ADMIN_USER", "2", "ADMIN_ROLE_UPDATE"));
        auditLogMapper.insertLog(auditLog(1L, "ACCOUNT", "200", "ACCOUNT_OPENED"));

        // adminKeyword는 au.name, targetKeyword는 target_admin.name/target_account.account_no를
        // 참조하는 WHERE 절을 타므로, 이 join들이 select뿐 아니라 count에도 없으면 "Unknown column" 에러가 난다.
        long totalForActor =
                auditLogMapper.countAuditLogs(searchDefaults().adminKeyword("박지훈").build());
        long totalForTargetAdmin =
                auditLogMapper.countAuditLogs(searchDefaults().targetKeyword("이국희").build());
        long totalForAccountOwnerAccountNo =
                auditLogMapper.countAuditLogs(searchDefaults().targetKeyword("9000000001").build());

        assertThat(totalForActor).isEqualTo(2);
        assertThat(totalForTargetAdmin).isEqualTo(1);
        assertThat(totalForAccountOwnerAccountNo).isEqualTo(1);
    }

    private void insertAdmin(Long adminId, String name, String role) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO admin_user (admin_id, name, role) VALUES ("
                            + adminId
                            + ", '"
                            + name
                            + "', '"
                            + role
                            + "')");
        }
    }

    private void insertAccount(Long accountId, String accountNo) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO account (account_id, account_no) VALUES ("
                            + accountId
                            + ", '"
                            + accountNo
                            + "')");
        }
    }

    private void insertSettlementBatch(Long batchId, String executedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO settlement_batch (batch_id, executed_at) VALUES ("
                            + batchId
                            + ", '"
                            + executedAt
                            + "')");
        }
    }

    private void insertLogAt(
            Long adminId,
            String targetTable,
            String targetPk,
            String reasonCode,
            String processedAt)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO audit_log (admin_id, target_table, target_pk, before_value, after_value, reason_code, processed_at) "
                            + "VALUES ("
                            + adminId
                            + ", '"
                            + targetTable
                            + "', '"
                            + targetPk
                            + "', 'VIEWER', 'ADMIN', '"
                            + reasonCode
                            + "', '"
                            + processedAt
                            + "')");
        }
    }

    private void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute(
                    """
                    CREATE TABLE admin_user (
                        admin_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name     VARCHAR(50) NOT NULL,
                        role     VARCHAR(20) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE account (
                        account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        account_no VARCHAR(20) NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE settlement_batch (
                        batch_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
                        executed_at DATETIME NOT NULL
                    )
                    """);
            statement.execute(
                    """
                    CREATE TABLE audit_log (
                        audit_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
                        admin_id     BIGINT NOT NULL,
                        target_table VARCHAR(50) NOT NULL,
                        target_pk    VARCHAR(50) NOT NULL,
                        before_value TEXT NULL,
                        after_value  TEXT NULL,
                        reason_code  VARCHAR(30) NULL,
                        processed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        recorded_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
    }
}
