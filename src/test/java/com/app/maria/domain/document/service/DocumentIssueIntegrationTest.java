package com.app.maria.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.app.maria.domain.document.dto.DocumentFileDTO;
import com.app.maria.domain.document.dto.response.DocumentIssueResponseDTO;
import com.app.maria.domain.tax.dto.TaxBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.service.BusinessClockService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 서비스·MyBatis 매퍼·템플릿 엔진·PDF 렌더러·H2를 쓴다. 세액 재계산, 업무시각, 감사로그만 대체한다. 테스트는 트랜잭션을 열지 않으므로 서비스 호출 뒤 별도
 * 연결로 커밋·롤백 결과를 확인한다. H2 검증이며 MariaDB의 잠금 동작 검증을 대체하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("documenttest")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class DocumentIssueIntegrationTest {

    private static final long CUSTOMER_ID = 7001L;
    private static final long ACCOUNT_ID = 7001L;
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 4, 1, 10, 0);

    @Autowired private DocumentIssueService service;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private BusinessClockService clockService;
    @MockitoBean private TaxCalculationService taxCalculationService;
    @MockitoBean private AuditLogService auditLogService;
    @MockitoBean private AuditActorProvider auditActorProvider;

    @BeforeAll
    void createSchema() {
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS customer (
                    customer_id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL,
                    birth_date DATE NOT NULL, phone VARCHAR(20), investor_type VARCHAR(20) NOT NULL,
                    ci_hash VARCHAR(64) NOT NULL UNIQUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS account (
                    account_id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL UNIQUE REFERENCES customer(customer_id),
                    status VARCHAR(20) NOT NULL, opened_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    account_no VARCHAR(10) UNIQUE, limit_amount DECIMAL(15,0) NOT NULL,
                    amount DECIMAL(15,0) NOT NULL DEFAULT 0, benefit VARCHAR(12)
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS tax_rule (
                    rule_id BIGINT PRIMARY KEY AUTO_INCREMENT, rule_type VARCHAR(20) NOT NULL,
                    rule_value DECIMAL(15,4) NOT NULL, valid_from DATE NOT NULL, valid_to DATE NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, created_by VARCHAR(30)
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS tax_calculation (
                    calc_id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id BIGINT NOT NULL,
                    calculated_at DATETIME NOT NULL, basis_type VARCHAR(30) NOT NULL,
                    weighted_sell DECIMAL(15,2) NOT NULL, original_gain_amount DECIMAL(15,2) NOT NULL,
                    weighted_gain DECIMAL(15,2) NOT NULL, weighted_external_amount DECIMAL(15,2) NOT NULL,
                    adjust_ratio DECIMAL(7,4) NOT NULL, final_deduction DECIMAL(15,2) NOT NULL,
                    final_tax DECIMAL(15,2) NOT NULL,
                    CONSTRAINT uk_tc_account_basis UNIQUE (account_id, basis_type)
                )
                """);
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS document_issue (
                    document_issue_id BIGINT PRIMARY KEY AUTO_INCREMENT, account_id BIGINT NOT NULL,
                    calc_id BIGINT NOT NULL, doc_type VARCHAR(30) NOT NULL,
                    issue_no VARCHAR(30) NOT NULL UNIQUE, status VARCHAR(10) NOT NULL,
                    file_content MEDIUMBLOB NOT NULL, file_hash CHAR(64) NOT NULL,
                    issued_by_type VARCHAR(10) NOT NULL, issued_by_id BIGINT NOT NULL,
                    issued_at DATETIME NOT NULL, revoked_at DATETIME NULL,
                    recorded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM document_issue");
        jdbc.update("DELETE FROM tax_calculation");
        jdbc.update("DELETE FROM tax_rule");
        jdbc.update("DELETE FROM account WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("DELETE FROM customer WHERE customer_id = ?", CUSTOMER_ID);
        jdbc.update(
                "INSERT INTO customer (customer_id, name, birth_date, investor_type, ci_hash)"
                        + " VALUES (?, '통합테스트고객', '1990-01-01', 'NEUTRAL', 'document-it-ci')",
                CUSTOMER_ID);
        jdbc.update(
                "INSERT INTO account (account_id, customer_id, status, opened_at, account_no,"
                        + " limit_amount, benefit) VALUES (?, ?, 'OPENED', '2026-03-24 09:00:00',"
                        + " '7000000001', 50000000, 'POSSIBLE')",
                ACCOUNT_ID,
                CUSTOMER_ID);
        jdbc.update(
                "INSERT INTO tax_rule (rule_type, rule_value, valid_from, valid_to) VALUES"
                        + " ('BASIC_DEDUCTION', 2500000, '2026-01-01', '9999-12-31'),"
                        + " ('TAX_RATE', 0.22, '2026-01-01', '9999-12-31')");
        jdbc.update(
                "INSERT INTO tax_calculation (account_id, calculated_at, basis_type, weighted_sell,"
                        + " original_gain_amount, weighted_gain, weighted_external_amount,"
                        + " adjust_ratio, final_deduction, final_tax) VALUES (?, '2027-03-01 09:00:00',"
                        + " 'FINAL_REPORT', 43000000, 32000000, 27800000, 11000000, 0.7442,"
                        + " 20688760, 1938472.80)",
                ACCOUNT_ID);

        reset(clockService, taxCalculationService, auditLogService, auditActorProvider);
        when(clockService.now()).thenReturn(NOW);
        when(auditActorProvider.getCurrentAdminId()).thenReturn(1L);
        when(taxCalculationService.taxCalculate(ACCOUNT_ID)).thenReturn(matchingPreview());
    }

    private TaxCalculationPreviewResponseDTO matchingPreview() {
        TaxCalculationResultDTO result =
                TaxCalculationResultDTO.builder()
                        .weightedSell(new BigDecimal("43000000"))
                        .originalGainAmount(new BigDecimal("32000000"))
                        .weightedGain(new BigDecimal("27800000"))
                        .weightedExternalAmount(new BigDecimal("11000000"))
                        .adjustRatio(new BigDecimal("0.7442"))
                        .finalDeduction(new BigDecimal("20688760"))
                        .finalTax(new BigDecimal("1938472.80"))
                        .build();
        TaxBreakdownDTO breakdown =
                TaxBreakdownDTO.of(
                        List.of(
                                period(1, 5, "1.0000", "30000000", "20000000"),
                                period(6, 7, "0.8000", "10000000", "6000000"),
                                period(8, 12, "0.5000", "10000000", "6000000")),
                        List.of(),
                        List.of());
        return TaxCalculationPreviewResponseDTO.builder()
                .accountId(ACCOUNT_ID)
                .taxCalculationResultDTO(result)
                .breakdown(breakdown)
                .build();
    }

    private TaxPeriodBreakdownDTO period(
            int fromMonth, int toMonth, String weight, String sell, String gain) {
        return TaxPeriodBreakdownDTO.builder()
                .validFrom(LocalDate.of(2026, fromMonth, 1))
                .validTo(LocalDate.of(2026, toMonth, 28))
                .weight(new BigDecimal(weight))
                .sellAmount(new BigDecimal(sell))
                .gainAmount(new BigDecimal(gain))
                .externalNetBuyAmount(BigDecimal.ZERO)
                .build();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    @DisplayName("고객 발급 -> 저장 -> 다운로드: 실제 PDF가 DB에 저장되고 해시가 맞아 그대로 내려받는다")
    void issue_store_download() throws Exception {
        DocumentIssueResponseDTO issued = service.issueForCustomer(CUSTOMER_ID);

        assertThat(count("SELECT COUNT(*) FROM document_issue WHERE status = 'ISSUED'"))
                .isEqualTo(1);
        byte[] stored =
                jdbc.queryForObject(
                        "SELECT file_content FROM document_issue WHERE document_issue_id = ?",
                        byte[].class,
                        issued.getDocumentIssueId());
        String storedHash =
                jdbc.queryForObject(
                        "SELECT file_hash FROM document_issue WHERE document_issue_id = ?",
                        String.class,
                        issued.getDocumentIssueId());
        assertThat(new String(stored, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(storedHash)
                .isEqualTo(
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(stored)));

        DocumentFileDTO file =
                service.downloadForCustomer(CUSTOMER_ID, issued.getDocumentIssueId());
        assertThat(file.getContent()).isEqualTo(stored);
        try (PDDocument pdf = PDDocument.load(file.getContent())) {
            String text = new PDFTextStripper().getText(pdf);
            assertThat(text)
                    .contains(issued.getIssueNo())
                    .contains("통합테스트고객")
                    .contains("7000000001")
                    .contains("1,938,473원");
        }
    }

    @Test
    @DisplayName("같은 계좌에서 두 번 발급해도 증빙은 하나만 남는다")
    void issueTwice_keepsOne() {
        DocumentIssueResponseDTO first = service.issueForCustomer(CUSTOMER_ID);
        DocumentIssueResponseDTO second = service.issueForCustomer(CUSTOMER_ID);

        assertThat(second.getDocumentIssueId()).isEqualTo(first.getDocumentIssueId());
        assertThat(count("SELECT COUNT(*) FROM document_issue")).isEqualTo(1);
    }

    @Test
    @DisplayName("발급 도중 실패하면(감사로그 저장 실패) 증빙 행도 함께 롤백된다")
    void adminIssue_rollsBackWhenAuditFails() {
        doThrow(new IllegalStateException("audit down")).when(auditLogService).log(any());

        assertThatThrownBy(() -> service.issueForAdmin(ACCOUNT_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT COUNT(*) FROM document_issue")).isZero();
    }

    @Test
    @DisplayName("조기인출로 혜택이 취소된 뒤에는 새로 발급되지 않는다")
    void issue_afterBenefitCanceled_rejected() {
        jdbc.update("UPDATE account SET benefit = 'IMPOSSIBLE' WHERE account_id = ?", ACCOUNT_ID);

        assertThatThrownBy(() -> service.issueForCustomer(CUSTOMER_ID))
                .hasMessageContaining("세제혜택이 취소");
        assertThat(count("SELECT COUNT(*) FROM document_issue")).isZero();
    }
}
