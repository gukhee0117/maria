package com.app.maria.global.exception;

import com.app.maria.domain.foreignproduct.exception.ForeignProductException;
import com.app.maria.domain.foreignproduct.exception.ForeignProductNotFoundException;
import com.app.maria.domain.member.exception.MemberException;
import com.app.maria.domain.member.exception.MemberNotFoundException;
import com.app.maria.global.error.AppException;
import com.app.maria.global.response.ApiResponseDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
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

    // 16. AppException — 새로 만든 예외 구조 하나가 처리함. 아래 3~19번처럼 예외 종류마다
    // 핸들러를 따로 안 만들어도 됨 (Tax/SellOrder/KIS/환율/Admin/AuditLog/Inbound/Domestic/TargetProduct
    // 도메인이 이 구조로 옮겨짐.
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

    // Spring Security 예외(403/401)는 여기서 잡지 않고 Security 필터 체인에 위임한다.
    // @ExceptionHandler(Exception.class) 폴백이 먼저 낚아채면 403→500으로 바뀌는 문제를 막기 위함.
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(Exception e) throws Exception {
        throw e;
    }

    // 폴백 — 위 핸들러에서 분류되지 않은 예상 밖 오류. ApiResponseDTO 형식을 보장해
    // 화면 JS가 빈 메시지를 띄우는 일을 막는다.
    // Spring MVC가 자체적으로 던지는 4xx ErrorResponse(예: 405 MethodNotAllowed)는
    // 해당 상태 코드를 그대로 돌려줘서 500으로 뭉개지 않도록 한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleUnexpectedException(Exception e) {
        HttpStatusCode status =
                (e instanceof ErrorResponse er)
                        ? er.getStatusCode()
                        : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            log.error("[UNEXPECTED_ERROR]", e);
        }
        return ResponseEntity.status(status).body(ApiResponseDTO.of("서버 내부 오류가 발생했습니다."));
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
}
