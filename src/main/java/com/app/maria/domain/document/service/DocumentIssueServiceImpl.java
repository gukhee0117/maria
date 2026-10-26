package com.app.maria.domain.document.service;

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
import com.app.maria.domain.document.type.DocumentAuditLogReasonCode;
import com.app.maria.domain.document.type.DocumentStatus;
import com.app.maria.domain.document.type.DocumentType;
import com.app.maria.domain.document.type.IssuedByType;
import com.app.maria.domain.tax.dto.TaxCalculationDTO;
import com.app.maria.domain.tax.dto.TaxCalculationResultDTO;
import com.app.maria.domain.tax.dto.response.TaxCalculationPreviewResponseDTO;
import com.app.maria.domain.tax.mapper.TaxMapper;
import com.app.maria.domain.tax.service.TaxCalculationService;
import com.app.maria.domain.tax.type.TaxBasisType;
import com.app.maria.global.audit.dto.AuditLogDTO;
import com.app.maria.global.audit.provider.AuditActorProvider;
import com.app.maria.global.audit.service.AuditLogService;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.DocumentProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentIssueServiceImpl implements DocumentIssueService {

    private static final String TARGET_TABLE = "DOCUMENT_ISSUE";
    private static final String ISSUE_NO_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ISSUE_NO_RANDOM_LENGTH = 8;

    private final DocumentIssueMapper documentIssueMapper;
    private final AccountMapper accountMapper;
    private final TaxMapper taxMapper;
    private final TaxCalculationService taxCalculationService;
    private final TaxReliefCertViewAssembler viewAssembler;
    private final TaxReliefCertPdfRenderer pdfRenderer;
    private final BusinessClockService clockService;
    private final DocumentProperties documentProperties;
    private final AuditLogService auditLogService;
    private final AuditActorProvider auditActorProvider;

    // ponytail: 발급번호는 무작위 8자(32^8). 겹치면 UNIQUE가 막아 발급이 실패한다. 재시도는 필요해지면 추가.
    private final SecureRandom random = new SecureRandom();

    @Override
    @Transactional
    public DocumentIssueResponseDTO issueForCustomer(Long customerId) {
        AccountDTO account = findAccountByCustomer(customerId);
        return issue(account.getAccountId(), IssuedByType.CUSTOMER, customerId).response();
    }

    @Override
    @Transactional
    public DocumentIssueResponseDTO issueForAdmin(Long accountId) {
        Long adminId = auditActorProvider.getCurrentAdminId();
        Issued issued = issue(accountId, IssuedByType.ADMIN, adminId);
        if (issued.created()) {
            audit(
                    adminId,
                    issued.response().getDocumentIssueId(),
                    DocumentAuditLogReasonCode.DOCUMENT_ISSUED);
        }
        return issued.response();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentIssueResponseDTO> getByCustomer(Long customerId) {
        return getByAccount(findAccountByCustomer(customerId).getAccountId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentIssueResponseDTO> getByAccount(Long accountId) {
        return documentIssueMapper.selectByAccountId(accountId).stream()
                .map(DocumentIssueResponseDTO::of)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentFileDTO downloadForCustomer(Long customerId, Long documentIssueId) {
        AccountDTO account = findAccountByCustomer(customerId);
        DocumentIssueDTO issue = findWithFile(documentIssueId);
        // 남의 증빙은 존재 여부도 알려주지 않는다.
        if (!issue.getAccountId().equals(account.getAccountId())) {
            throw new AppException(ErrorType.DOCUMENT_NOT_FOUND, documentIssueId);
        }
        if (issue.getStatus() == DocumentStatus.REVOKED) {
            throw new AppException(ErrorType.DOCUMENT_REVOKED, documentIssueId);
        }
        return toFile(issue);
    }

    @Override
    @Transactional
    public DocumentFileDTO downloadForAdmin(Long documentIssueId) {
        DocumentFileDTO file = toFile(findWithFile(documentIssueId));
        audit(
                auditActorProvider.getCurrentAdminId(),
                documentIssueId,
                DocumentAuditLogReasonCode.DOCUMENT_DOWNLOADED);
        return file;
    }

    private Issued issue(Long accountId, IssuedByType byType, Long byId) {
        // 같은 계좌의 동시 발급·재계산을 한 줄로 세운다.
        AccountDTO account =
                accountMapper
                        .selectByAccountIdForUpdate(accountId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, accountId));

        LocalDateTime now = clockService.now();
        if (now.toLocalDate().isBefore(documentProperties.getIssueFrom())) {
            throw new AppException(ErrorType.DOCUMENT_ISSUE_NOT_OPEN, accountId);
        }
        if (BenefitType.isReliefExcluded(account.getBenefit())) {
            throw new AppException(ErrorType.DOCUMENT_BENEFIT_CANCELED, accountId);
        }

        TaxCalculationDTO calc =
                taxMapper
                        .selectCalculationByAccountAndBasis(accountId, TaxBasisType.FINAL_REPORT)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorType.DOCUMENT_FINAL_REPORT_NOT_FOUND,
                                                accountId));

        // 이미 발급된 증빙이 있으면 새로 만들지 않고 그대로 돌려준다.
        Optional<DocumentIssueDTO> existing =
                documentIssueMapper.selectIssuedByAccountId(accountId);
        if (existing.isPresent()) {
            return new Issued(DocumentIssueResponseDTO.of(existing.get()), false);
        }

        TaxCalculationPreviewResponseDTO preview = taxCalculationService.taxCalculate(accountId);
        verifyMatchesStored(accountId, calc, preview.getTaxCalculationResultDTO());

        String issueNo = newIssueNo(now);
        String holderName =
                documentIssueMapper
                        .selectHolderName(accountId)
                        .orElseThrow(
                                () -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, accountId));
        TaxReliefCertView view =
                viewAssembler.assemble(
                        issueNo,
                        now,
                        holderName,
                        account,
                        calc,
                        preview.getBreakdown(),
                        taxMapper.selectTaxRules());
        byte[] pdf = pdfRenderer.render(view);

        DocumentIssueDTO issue =
                DocumentIssueDTO.builder()
                        .accountId(accountId)
                        .calcId(calc.getCalcId())
                        .docType(DocumentType.TAX_RELIEF_CERT)
                        .issueNo(issueNo)
                        .status(DocumentStatus.ISSUED)
                        .fileContent(pdf)
                        .fileHash(sha256(pdf))
                        .issuedByType(byType)
                        .issuedById(byId)
                        .issuedAt(now)
                        .build();
        documentIssueMapper.insert(issue);
        return new Issued(DocumentIssueResponseDTO.of(issue), true);
    }

    private record Issued(DocumentIssueResponseDTO response, boolean created) {}

    /** 저장된 확정 세액과 지금 다시 계산한 결과가 다르면 발급하지 않는다. */
    private void verifyMatchesStored(
            Long accountId, TaxCalculationDTO stored, TaxCalculationResultDTO fresh) {
        boolean same =
                same(stored.getWeightedSell(), fresh.getWeightedSell())
                        && same(stored.getOriginalGainAmount(), fresh.getOriginalGainAmount())
                        && same(stored.getWeightedGain(), fresh.getWeightedGain())
                        && same(
                                stored.getWeightedExternalAmount(),
                                fresh.getWeightedExternalAmount())
                        && same(stored.getAdjustRatio(), fresh.getAdjustRatio())
                        && same(stored.getFinalDeduction(), fresh.getFinalDeduction())
                        && same(stored.getFinalTax(), fresh.getFinalTax());
        if (!same) {
            throw new AppException(ErrorType.DOCUMENT_BASIS_MISMATCH, accountId);
        }
    }

    private boolean same(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0;
    }

    private DocumentFileDTO toFile(DocumentIssueDTO issue) {
        byte[] content = issue.getFileContent();
        if (content == null || !sha256(content).equals(issue.getFileHash())) {
            throw new AppException(ErrorType.DOCUMENT_FILE_CORRUPTED, issue.getDocumentIssueId());
        }
        return new DocumentFileDTO("RIA증빙서_" + issue.getIssueNo() + ".pdf", content);
    }

    private DocumentIssueDTO findWithFile(Long documentIssueId) {
        return documentIssueMapper
                .selectWithFileById(documentIssueId)
                .orElseThrow(() -> new AppException(ErrorType.DOCUMENT_NOT_FOUND, documentIssueId));
    }

    private AccountDTO findAccountByCustomer(Long customerId) {
        return accountMapper
                .selectByCustomerId(customerId)
                .orElseThrow(() -> new AppException(ErrorType.ACCOUNT_NOT_FOUND, customerId));
    }

    private void audit(Long adminId, Long documentIssueId, DocumentAuditLogReasonCode reason) {
        auditLogService.log(
                AuditLogDTO.builder()
                        .adminId(adminId)
                        .targetTable(TARGET_TABLE)
                        .targetPk(String.valueOf(documentIssueId))
                        .afterValue(reason.name())
                        .reasonCode(reason.name())
                        .build());
    }

    private String newIssueNo(LocalDateTime now) {
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < ISSUE_NO_RANDOM_LENGTH; i++) {
            suffix.append(ISSUE_NO_ALPHABET.charAt(random.nextInt(ISSUE_NO_ALPHABET.length())));
        }
        return "RIA-" + now.getYear() + "-" + suffix;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
