package com.app.maria.global.exception;

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
import com.app.maria.domain.settlement.exception.*;
import com.app.maria.domain.targetproduct.exception.TargetProductException;
import com.app.maria.domain.targetproduct.exception.TargetProductNotFoundException;
import com.app.maria.global.error.AppException;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Slf4j
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

    // 16. AppException — 새로 만든 예외 구조 하나가 처리함. 아래 3~19번처럼 예외 종류마다
    // 핸들러를 따로 안 만들어도 됨 (Tax/SellOrder/KIS/환율/Admin/AuditLog 도메인이 이 구조로 옮겨짐.
    // 나머지 도메인은 아직 밑에 그대로 있음 — 자기 도메인 옮길 땐 밑에 있는 해당 핸들러 지우고,
    // 예외 던지는 곳을 AppException으로 바꾸면 됨 — 자세한 건 ErrorType.java / AppException.java 주석 참고)
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleAppException(AppException e) {
        logAppException(e);
        // 응답엔 ErrorType에 적어둔 고정 메시지 + code(=ErrorType 이름)가 나감.
        // code는 지금 화면(JS)에선 안 쓰지만, 2차 React 화면에서 에러 종류별로 분기할 때 쓸 값.
        return ResponseEntity.status(e.getErrorType().getStatus())
                .body(ApiResponseDTO.error(e.getErrorType().name(), e.getMessage()));
    }

    // 심각도(logLevel)에 따라 로그 레벨을 나눠 찍는다. errorData(예: accountId)는 여기 로그에만
    // 남고 사용자 응답엔 안 나간다. e를 그대로 넘겨서 스택트레이스 전체가 로그에 같이 찍히게 함.
    private void logAppException(AppException e) {
        String logMessage = "[" + e.getErrorType().name() + "] errorData=" + e.getErrorData();
        switch (e.getErrorType().getLogLevel()) {
            case ERROR -> log.error(logMessage, e);
            case WARN -> log.warn(logMessage, e);
            default -> log.info(logMessage, e);
        }
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
