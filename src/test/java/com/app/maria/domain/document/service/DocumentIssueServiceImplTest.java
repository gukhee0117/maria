package com.app.maria.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.account.dto.AccountDTO;
import com.app.maria.domain.account.mapper.AccountMapper;
import com.app.maria.domain.account.type.BenefitType;
import com.app.maria.domain.document.component.TaxReliefCertPdfRenderer;
import com.app.maria.domain.document.component.TaxReliefCertView;
import com.app.maria.domain.document.component.TaxReliefCertViewAssembler;
import com.app.maria.domain.document.dto.DocumentFileDTO;
import com.app.maria.domain.document.dto.DocumentIssueDTO;
import com.app.maria.domain.document.dto.response.DocumentIssueResponseDTO;
import com.app.maria.domain.document.mapper.DocumentIssueMapper;
import com.app.maria.domain.document.type.DocumentStatus;
import com.app.maria.domain.document.type.IssuedByType;
import com.app.maria.domain.tax.dto.TaxBreakdownDTO;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.TaxPeriodBreakdownDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.domain.tax.type.TaxBasisType;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.DocumentProperties;
import com.app.maria.global.config.properties.RiaTaxProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentIssueServiceImplTest {

    private static final Long ACCOUNT_ID = 7L;
    private static final Long CUSTOMER_ID = 3L;
    private static final LocalDateTime OPEN_DAY = LocalDateTime.of(2027, 4, 1, 10, 0);
    private static final byte[] PDF = "%PDF-test".getBytes(StandardCharsets.UTF_8);

    @Mock DocumentIssueMapper documentIssueMapper;
    @Mock AccountMapper accountMapper;
    @Mock TaxMapper taxMapper;
    @Mock TaxCalculationService taxCalculationService;
    @Mock TaxReliefCertViewAssembler viewAssembler;
    @Mock TaxReliefCertPdfRenderer pdfRenderer;
    @Mock BusinessClockService clockService;
    @Mock AuditLogService auditLogService;
    @Mock AuditActorProvider auditActorProvider;

    @Mock RiaTaxProperties riaTaxProperties;

    DocumentProperties documentProperties = new DocumentProperties();

    DocumentIssueServiceImpl service;

    @BeforeEach
    void setUp() {
        // @InjectMocks는 생성자 주입이라 직접 만든 properties도 넣어야 한다.
        service =
                new DocumentIssueServiceImpl(
                        documentIssueMapper,
                        accountMapper,
                        taxMapper,
                        taxCalculationService,
                        viewAssembler,
                        pdfRenderer,
                        clockService,
                        documentProperties,
                        riaTaxProperties,
                        auditLogService,
                        auditActorProvider);
    }

    private AccountDTO account(BenefitType benefit) {
        return AccountDTO.builder()
                .accountId(ACCOUNT_ID)
                .customerId(CUSTOMER_ID)
                .benefit(benefit)
                .build();
    }

    private TaxCalculationDTO stored() {
        return TaxCalculationDTO.builder()
                .calcId(11L)
                .accountId(ACCOUNT_ID)
                .basisType(TaxBasisType.FINAL_REPORT)
                .weightedSell(new BigDecimal("43000000.00"))
                .originalGainAmount(new BigDecimal("32000000.00"))
                .weightedGain(new BigDecimal("27800000.00"))
                .weightedExternalAmount(new BigDecimal("11000000.00"))
                .adjustRatio(new BigDecimal("0.7442"))
                .finalDeduction(new BigDecimal("20688760.00"))
                .finalTax(new BigDecimal("1938473.00"))
                .build();
    }

    private TaxCalculationPreviewResponseDTO preview(BigDecimal finalTax) {
        TaxCalculationResultDTO result =
                TaxCalculationResultDTO.builder()
                        .weightedSell(new BigDecimal("43000000.0000"))
                        .originalGainAmount(new BigDecimal("32000000"))
                        .weightedGain(new BigDecimal("27800000"))
                        .weightedExternalAmount(new BigDecimal("11000000"))
                        .adjustRatio(new BigDecimal("0.7442"))
                        .finalDeduction(new BigDecimal("20688760"))
                        .finalTax(finalTax)
                        .build();
        return TaxCalculationPreviewResponseDTO.builder()
                .accountId(ACCOUNT_ID)
                .taxCalculationResultDTO(result)
                .breakdown(
                        TaxBreakdownDTO.of(
                                List.of(
                                        period(1, 5, "1.0000", "30000000", "20000000"),
                                        period(6, 7, "0.8000", "10000000", "6000000"),
                                        period(8, 12, "0.5000", "10000000", "6000000")),
                                List.of(),
                                List.of()))
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

    private void readyToIssue(BenefitType benefit) {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(benefit)));
        readyToIssueByAccount(benefit);
    }

    private void readyToIssueByAccount(BenefitType benefit) {
        org.mockito.Mockito.lenient().when(riaTaxProperties.getYear()).thenReturn(2026);
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(benefit)));
        when(clockService.now()).thenReturn(OPEN_DAY);
    }

    @Test
    @DisplayName("발급: 저장된 세액과 일치하면 PDF를 만들어 해시와 함께 저장한다")
    void issueForCustomer_savesPdfWithHash() {
        readyToIssue(BenefitType.POSSIBLE);
        when(taxMapper.selectCalculationByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(Optional.of(stored()));
        when(documentIssueMapper.selectIssuedByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(taxCalculationService.taxCalculate(ACCOUNT_ID))
                .thenReturn(preview(new BigDecimal("1938473")));
        when(documentIssueMapper.selectHolderName(ACCOUNT_ID)).thenReturn(Optional.of("홍길동"));
        when(viewAssembler.assemble(anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaxReliefCertView.builder().build());
        when(pdfRenderer.render(any())).thenReturn(PDF);

        DocumentIssueResponseDTO response = service.issueForCustomer(CUSTOMER_ID);

        ArgumentCaptor<DocumentIssueDTO> saved = ArgumentCaptor.forClass(DocumentIssueDTO.class);
        verify(documentIssueMapper).insert(saved.capture());
        DocumentIssueDTO row = saved.getValue();
        assertThat(row.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(row.getCalcId()).isEqualTo(11L);
        assertThat(row.getStatus()).isEqualTo(DocumentStatus.ISSUED);
        assertThat(row.getIssuedByType()).isEqualTo(IssuedByType.CUSTOMER);
        assertThat(row.getIssuedById()).isEqualTo(CUSTOMER_ID);
        assertThat(row.getIssuedAt()).isEqualTo(OPEN_DAY);
        assertThat(row.getFileContent()).isEqualTo(PDF);
        assertThat(row.getFileHash()).isEqualTo(sha256(PDF));
        assertThat(row.getIssueNo()).matches("RIA-2027-[A-Z2-9]{8}");
        assertThat(response.getIssueNo()).isEqualTo(row.getIssueNo());
        verify(auditLogService, never()).log(any());
    }

    @Test
    @DisplayName("발급: 발급 가능일 전이면 거부한다")
    void issue_beforeOpenDate_rejected() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(BenefitType.POSSIBLE)));
        when(accountMapper.selectByAccountIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(account(BenefitType.POSSIBLE)));
        when(clockService.now()).thenReturn(LocalDateTime.of(2027, 3, 31, 23, 59));

        assertThatThrownBy(() -> service.issueForCustomer(CUSTOMER_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.DOCUMENT_ISSUE_NOT_OPEN));
        verify(documentIssueMapper, never()).insert(any());
    }

    @Test
    @DisplayName("발급: 확정신고 행이 없으면 거부한다")
    void issue_withoutFinalReport_rejected() {
        readyToIssue(BenefitType.POSSIBLE);
        when(taxMapper.selectCalculationByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueForCustomer(CUSTOMER_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.DOCUMENT_FINAL_REPORT_NOT_FOUND));
    }

    @Test
    @DisplayName("발급: 혜택이 취소된 계좌는 거부한다")
    void issue_benefitCanceled_rejected() {
        readyToIssue(BenefitType.IMPOSSIBLE);

        assertThatThrownBy(() -> service.issueForCustomer(CUSTOMER_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.DOCUMENT_BENEFIT_CANCELED));
    }

    @Test
    @DisplayName("발급: 다시 계산한 값이 저장된 값과 다르면 거부한다")
    void issue_basisMismatch_rejected() {
        readyToIssue(BenefitType.POSSIBLE);
        when(taxMapper.selectCalculationByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(Optional.of(stored()));
        when(documentIssueMapper.selectIssuedByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(taxCalculationService.taxCalculate(ACCOUNT_ID))
                .thenReturn(preview(new BigDecimal("1938474")));

        assertThatThrownBy(() -> service.issueForCustomer(CUSTOMER_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.DOCUMENT_BASIS_MISMATCH));
        verify(pdfRenderer, never()).render(any());
        verify(documentIssueMapper, never()).insert(any());
    }

    @Test
    @DisplayName("발급: 구간별 표의 합이 저장된 합계와 다르면 거부한다")
    void issue_breakdownTotalsMismatch_rejected() {
        readyToIssue(BenefitType.POSSIBLE);
        when(taxMapper.selectCalculationByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(Optional.of(stored()));
        when(documentIssueMapper.selectIssuedByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        TaxCalculationPreviewResponseDTO matchingTotals = preview(new BigDecimal("1938473"));
        when(taxCalculationService.taxCalculate(ACCOUNT_ID))
                .thenReturn(
                        TaxCalculationPreviewResponseDTO.builder()
                                .accountId(ACCOUNT_ID)
                                .taxCalculationResultDTO(
                                        matchingTotals.getTaxCalculationResultDTO())
                                .breakdown(
                                        TaxBreakdownDTO.of(
                                                List.of(
                                                        period(
                                                                1,
                                                                5,
                                                                "1.0000",
                                                                "30000000",
                                                                "20000000")),
                                                List.of(),
                                                List.of()))
                                .build());

        assertThatThrownBy(() -> service.issueForCustomer(CUSTOMER_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.DOCUMENT_BASIS_MISMATCH));
        verify(pdfRenderer, never()).render(any());
    }

    @Test
    @DisplayName("발급: 이미 발급된 증빙이 있으면 새로 만들지 않고 그대로 돌려준다")
    void issue_existing_returnsSame() {
        readyToIssue(BenefitType.POSSIBLE);
        when(taxMapper.selectCalculationByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(Optional.of(stored()));
        when(documentIssueMapper.selectIssuedByAccountId(ACCOUNT_ID))
                .thenReturn(
                        Optional.of(
                                DocumentIssueDTO.builder()
                                        .documentIssueId(5L)
                                        .accountId(ACCOUNT_ID)
                                        .issueNo("RIA-2027-AAAAAAAA")
                                        .status(DocumentStatus.ISSUED)
                                        .build()));

        DocumentIssueResponseDTO response = service.issueForCustomer(CUSTOMER_ID);

        assertThat(response.getDocumentIssueId()).isEqualTo(5L);
        verify(pdfRenderer, never()).render(any());
        verify(documentIssueMapper, never()).insert(any());
    }

    @Test
    @DisplayName("관리자 발급: 새로 만들 때만 감사로그를 남기고 발급자는 관리자로 기록한다")
    void issueForAdmin_auditsOnlyWhenCreated() {
        readyToIssueByAccount(BenefitType.POSSIBLE);
        when(auditActorProvider.getCurrentAdminId()).thenReturn(99L);
        when(taxMapper.selectCalculationByAccountAndBasis(ACCOUNT_ID, TaxBasisType.FINAL_REPORT))
                .thenReturn(Optional.of(stored()));
        when(documentIssueMapper.selectIssuedByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());
        when(taxCalculationService.taxCalculate(ACCOUNT_ID))
                .thenReturn(preview(new BigDecimal("1938473")));
        when(documentIssueMapper.selectHolderName(ACCOUNT_ID)).thenReturn(Optional.of("홍길동"));
        when(viewAssembler.assemble(anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TaxReliefCertView.builder().build());
        when(pdfRenderer.render(any())).thenReturn(PDF);

        service.issueForAdmin(ACCOUNT_ID);

        ArgumentCaptor<DocumentIssueDTO> saved = ArgumentCaptor.forClass(DocumentIssueDTO.class);
        verify(documentIssueMapper).insert(saved.capture());
        assertThat(saved.getValue().getIssuedByType()).isEqualTo(IssuedByType.ADMIN);
        assertThat(saved.getValue().getIssuedById()).isEqualTo(99L);
        ArgumentCaptor<AuditLogDTO> audit = ArgumentCaptor.forClass(AuditLogDTO.class);
        verify(auditLogService).log(audit.capture());
        assertThat(audit.getValue().getAdminId()).isEqualTo(99L);
        assertThat(audit.getValue().getTargetTable()).isEqualTo("DOCUMENT_ISSUE");
    }

    private DocumentIssueDTO storedIssue(Long accountId, DocumentStatus status, byte[] file) {
        return DocumentIssueDTO.builder()
                .documentIssueId(5L)
                .accountId(accountId)
                .issueNo("RIA-2027-AAAAAAAA")
                .status(status)
                .fileContent(file)
                .fileHash(sha256(PDF))
                .build();
    }

    @Test
    @DisplayName("다운로드: 본인 증빙이면 파일명과 내용을 돌려준다")
    void downloadForCustomer_ok() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(BenefitType.POSSIBLE)));
        when(documentIssueMapper.selectWithFileById(5L))
                .thenReturn(Optional.of(storedIssue(ACCOUNT_ID, DocumentStatus.ISSUED, PDF)));

        DocumentFileDTO file = service.downloadForCustomer(CUSTOMER_ID, 5L);

        assertThat(file.getContent()).isEqualTo(PDF);
        assertThat(file.getFileName()).isEqualTo("RIA증빙서_RIA-2027-AAAAAAAA.pdf");
    }

    @Test
    @DisplayName("다운로드: 남의 증빙은 없는 것처럼 NOT_FOUND")
    void downloadForCustomer_otherAccount_notFound() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(BenefitType.POSSIBLE)));
        when(documentIssueMapper.selectWithFileById(5L))
                .thenReturn(Optional.of(storedIssue(999L, DocumentStatus.ISSUED, PDF)));

        assertThatThrownBy(() -> service.downloadForCustomer(CUSTOMER_ID, 5L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.DOCUMENT_NOT_FOUND));
    }

    @Test
    @DisplayName("다운로드: 취소된 증빙은 고객이 받을 수 없다")
    void downloadForCustomer_revoked_rejected() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(BenefitType.POSSIBLE)));
        when(documentIssueMapper.selectWithFileById(5L))
                .thenReturn(Optional.of(storedIssue(ACCOUNT_ID, DocumentStatus.REVOKED, PDF)));

        assertThatThrownBy(() -> service.downloadForCustomer(CUSTOMER_ID, 5L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.DOCUMENT_REVOKED));
    }

    @Test
    @DisplayName("다운로드: 저장된 해시와 파일이 다르면 보내지 않는다")
    void download_hashMismatch_rejected() {
        when(accountMapper.selectByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(account(BenefitType.POSSIBLE)));
        byte[] tampered = "%PDF-tampered".getBytes(StandardCharsets.UTF_8);
        when(documentIssueMapper.selectWithFileById(5L))
                .thenReturn(Optional.of(storedIssue(ACCOUNT_ID, DocumentStatus.ISSUED, tampered)));

        assertThatThrownBy(() -> service.downloadForCustomer(CUSTOMER_ID, 5L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.DOCUMENT_FILE_CORRUPTED));
    }

    @Test
    @DisplayName("관리자 다운로드: 취소된 증빙은 내려주지 않는다")
    void downloadForAdmin_revoked_rejected() {
        when(documentIssueMapper.selectWithFileById(5L))
                .thenReturn(Optional.of(storedIssue(ACCOUNT_ID, DocumentStatus.REVOKED, PDF)));

        assertThatThrownBy(() -> service.downloadForAdmin(5L))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.DOCUMENT_REVOKED));
        verify(auditLogService, never()).log(any());
    }

    @Test
    @DisplayName("관리자 다운로드: 감사로그를 남긴다")
    void downloadForAdmin_audits() {
        when(auditActorProvider.getCurrentAdminId()).thenReturn(99L);
        when(documentIssueMapper.selectWithFileById(5L))
                .thenReturn(Optional.of(storedIssue(ACCOUNT_ID, DocumentStatus.ISSUED, PDF)));

        DocumentFileDTO file = service.downloadForAdmin(5L);

        assertThat(file.getContent()).isEqualTo(PDF);
        verify(auditLogService).log(any(AuditLogDTO.class));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
