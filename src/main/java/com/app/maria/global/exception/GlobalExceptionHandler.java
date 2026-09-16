package com.app.maria.global.exception;

import com.app.maria.domain.account.exception.AccountException;
import com.app.maria.domain.account.exception.AccountNotFoundException;
import com.app.maria.domain.account.exception.DuplicateAccountException;
import com.app.maria.domain.account.exception.InvalidAccountRequestException;
import com.app.maria.domain.accountclosure.exception.AccountClosureException;
import com.app.maria.domain.accountclosure.exception.AccountClosureNotFoundException;
import com.app.maria.domain.accountclosure.exception.AccountClosureProcessingException;
import com.app.maria.domain.accountclosure.exception.AccountClosureStateConflictException;
import com.app.maria.domain.admin.exception.AdminException;
import com.app.maria.domain.admin.exception.AdminNotFoundException;
import com.app.maria.domain.domestic.exception.DomesticInvestmentException;
import com.app.maria.domain.domestic.exception.DomesticInvestmentNotFoundException;
import com.app.maria.domain.domestic.exception.DomesticProductException;
import com.app.maria.domain.domestic.exception.DomesticProductNotFoundException;
import com.app.maria.domain.foreignproduct.exception.ForeignProductException;
import com.app.maria.domain.foreignproduct.exception.ForeignProductNotFoundException;
import com.app.maria.domain.inbound.exception.InboundException;
import com.app.maria.domain.inbound.exception.InboundNotFoundException;
import com.app.maria.domain.member.exception.MemberException;
import com.app.maria.domain.member.exception.MemberNotFoundException;
import com.app.maria.domain.sellorder.exception.SellOrderException;
import com.app.maria.domain.sellorder.exception.SellOrderNotFoundException;
import com.app.maria.domain.settlement.exception.*;
import com.app.maria.domain.targetproduct.exception.TargetProductException;
import com.app.maria.domain.targetproduct.exception.TargetProductNotFoundException;
import com.app.maria.domain.tax.exception.TaxCalculationAlreadyExistsException;
import com.app.maria.domain.tax.exception.TaxCalculationException;
import com.app.maria.domain.tax.exception.TaxRuleNotFoundException;
import com.app.maria.domain.withdrawal.exception.WithdrawalException;
import com.app.maria.domain.withdrawal.exception.WithdrawalNotFoundException;
import com.app.maria.domain.withdrawal.exception.WithdrawalProcessingException;
import com.app.maria.global.audit.exception.AuditLogException;
import com.app.maria.global.audit.exception.AuditLogInsertException;
import com.app.maria.global.audit.exception.AuditLogNotFoundException;
import com.app.maria.global.clock.exception.SystemClockNotInitializedException;
import com.app.maria.global.clock.exception.SystemClockUpdateException;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. DTO Valid 검증 예외
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException e) {
        String message =
                e.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(DefaultMessageSourceResolvable::getDefaultMessage)
                        .orElse("요청값이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(ApiResponseDTO.of(message));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleMethodValidationException(
            HandlerMethodValidationException e) {
        String message =
                e.getAllErrors().stream()
                        .findFirst()
                        .map(MessageSourceResolvable::getDefaultMessage)
                        .orElse("요청값이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(ApiResponseDTO.of(message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleConstraintViolationException(
            ConstraintViolationException e) {
        String message =
                e.getConstraintViolations().stream()
                        .findFirst()
                        .map(ConstraintViolation::getMessage)
                        .orElse("요청값이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(ApiResponseDTO.of(message));
    }

    // 2. Member 예외
    @ExceptionHandler(MemberException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleException(MemberException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(MemberNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleMemberNotFound(MemberNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 3. SellOrder 예외
    @ExceptionHandler(SellOrderException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSellOrderException(SellOrderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SellOrderNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSellOrderNotFound(
            SellOrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(ExchangeRateNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleExchangeRateNotFoundException(
            ExchangeRateNotFoundException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(KisTokenIssueException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleKisTokenIssueException(
            KisTokenIssueException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(KisPriceNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleKisPriceNotFoundException(
            KisPriceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(UnsupportedExchangeException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleUnsupportedExchangeException(
            UnsupportedExchangeException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    // 4. Account 예외
    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAccountException(AccountException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAccountNotFoundException(
            AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleDuplicateAccountException(
            DuplicateAccountException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidAccountRequestException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleInvalidAccountRequestException(
            InvalidAccountRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    // 5. Admin 예외
    @ExceptionHandler(AdminException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAdminException(AdminException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(AdminNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAdminNotFoundException(
            AdminNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 6. Inbound 예외
    @ExceptionHandler(InboundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleInboundException(InboundException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(InboundNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleInboundNotFoundException(
            InboundNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 7. Settlement 예외
    @ExceptionHandler(SettlementException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementException(SettlementException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidSettlementException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleInvalidSettlementException(
            InvalidSettlementException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SettlementBatchNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementBatchNotFoundException(
            SettlementBatchNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SettlementItemNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementItemNotFoundException(
            SettlementItemNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SettlementCalculationException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementCalculationException(
            SettlementCalculationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SettlementStateConflictException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementStateConflictException(
            SettlementStateConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SettlementBatchAlreadyRunningException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementBatchAlreadyRunningException(
            SettlementBatchAlreadyRunningException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(KrwExchangeNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleKrwExchangeNotFoundException(
            KrwExchangeNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SettlementAccountMismatchException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementAccountMismatchException(
            SettlementAccountMismatchException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SettlementAccountNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSettlementAccountNotFoundException(
            SettlementAccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 8. mydata 예외
    @ExceptionHandler(MydataApiException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleMydataApiException(MydataApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    // 9. ForeignProduct 예외
    @ExceptionHandler(ForeignProductException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleForeignProductException(
            ForeignProductException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(ForeignProductNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleForeignProductNotFound(
            ForeignProductNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 10. Withdrawal 예외
    @ExceptionHandler(WithdrawalNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleWithdrawalNotFoundException(
            WithdrawalNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(WithdrawalException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleWithdrawalException(WithdrawalException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(WithdrawalProcessingException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleWithdrawalProcessingException(
            WithdrawalProcessingException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    // 11. Clock 예외
    @ExceptionHandler(SystemClockNotInitializedException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSystemClockNotInitializedException(
            SystemClockNotInitializedException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(SystemClockUpdateException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleSystemClockUpdateException(
            SystemClockUpdateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 12. Audit 예외
    @ExceptionHandler(AuditLogException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAuditLogException(AuditLogException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(AuditLogInsertException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAuditLogInsertException(
            AuditLogInsertException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(AuditLogNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAuditLogNotFoundException(
            AuditLogNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 13. DomesticProduct 예외
    @ExceptionHandler(DomesticProductException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleDomesticProductException(
            DomesticProductException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(DomesticProductNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleDomesticProductNotFound(
            DomesticProductNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 14. 가환전 처리 예외
    @ExceptionHandler(ProvisionalException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleProvisionalException(ProvisionalException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    // 15. GeneralAccount 예외
    @ExceptionHandler(GeneralAccountApiException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleGeneralAccountApiException(
            GeneralAccountApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    // 16. 세액 계산 예외
    @ExceptionHandler(TaxCalculationException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleTaxCalculationException(
            TaxCalculationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(TaxRuleNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleTaxRuleNotFound(TaxRuleNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(TaxCalculationAlreadyExistsException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleTaxCalculationAlreadyExists(
            TaxCalculationAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 17. 계좌 해지 예외
    @ExceptionHandler(AccountClosureNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAccountClosureNotFoundException(
            AccountClosureNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(AccountClosureException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAccountClosureException(
            AccountClosureException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(AccountClosureProcessingException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAccountClosureProcessingException(
            AccountClosureProcessingException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(AccountClosureStateConflictException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAccountClosureStateConflictException(
            AccountClosureStateConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 18. 국내 투자 예외
    @ExceptionHandler(DomesticInvestmentException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleDomesticInvestmentException(
            DomesticInvestmentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(DomesticInvestmentNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleDomesticInvestmentNotFound(
            DomesticInvestmentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }

    // 19. TargetProduct 예외
    @ExceptionHandler(TargetProductException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleTargetProductException(
            TargetProductException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponseDTO.of(e.getMessage()));
    }

    @ExceptionHandler(TargetProductNotFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleTargetProductNotFoundException(
            TargetProductNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseDTO.of(e.getMessage()));
    }
}
