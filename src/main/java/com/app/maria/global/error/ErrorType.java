package com.app.maria.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.logging.LogLevel;
import org.springframework.http.HttpStatus;

/**
 * "이런 에러가 있다"를 한 줄씩 미리 정의해두는 목록이다. 예전처럼 예외마다 클래스 파일을 따로 만들지 않고, 여기 값 하나 추가하는 걸로 끝낸다.
 *
 * <p>각 값은 4개를 같이 들고 있다: HTTP 상태코드, 프론트에 그대로 보여줄 고정 메시지, code(별도로 안 적고 enum 이름 그대로 씀. 예:
 * TAX_RULE_NOT_FOUND), 로그 레벨.
 *
 * <p><b>내 도메인 예외를 여기로 옮기고 싶으면</b> — 이 enum에 값 하나 추가하면 된다:
 *
 * <pre>{@code
 * WITHDRAWAL_NOT_FOUND(HttpStatus.NOT_FOUND, "인출 내역을 찾을 수 없습니다.", LogLevel.WARN),
 * }</pre>
 *
 * <p>그리고 예외를 던질 땐 이렇게: {@code throw new AppException(ErrorType.WITHDRAWAL_NOT_FOUND, id);} (뒤에 붙인
 * id는 로그에만 남고 사용자한테 보이는 메시지엔 안 나감 — 자세한 건 AppException 주석 참고)
 *
 * <p><b>로그 레벨은 거의 WARN이면 된다.</b> "정상적으로 있을 수 있는 상황"(사용자가 잘못 요청했다, 동시에 두 요청이 겹쳤다 등)은 WARN. 앱이 제대로
 * 응답을 못 만들 정도로 진짜 심각한 경우에만 ERROR를 쓴다 (예: DB 연결이 끊겼다, 외부 API가 통째로 죽었다). 지금 tax 도메인 3개는 전부 WARN.
 *
 * <p>Tax/SellOrder/KIS/환율/Admin/AuditLog 도메인이 이 구조로 옮겨짐. 나머지 도메인은 아직 옛날 방식(exception 클래스 + 핸들러) 그대로
 * — 각자 담당 도메인 옮길 때 여기 참고해서 값 추가하면 됨.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorType {
    // 세금
    TAX_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "유효한 세액 규칙을 찾지 못했습니다.", LogLevel.WARN),
    TAX_FINAL_REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 확정신고된 계좌입니다.", LogLevel.WARN),
    TAX_EARLY_WITHDRAWAL_CLAWBACK_ALREADY_EXISTS(
            HttpStatus.CONFLICT, "이미 조기인출 정정이 처리된 계좌입니다.", LogLevel.WARN),
    WITHDRAWAL_NOT_FOUND(HttpStatus.NOT_FOUND, "인출 내역을 찾을 수 없습니다.", LogLevel.WARN),
    WITHDRAWAL_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "인출할 수 없는 요청입니다.", LogLevel.WARN),
    EARLY_WITHDRAWAL_CONSENT_REQUIRED(
            HttpStatus.BAD_REQUEST, "미경과 원금을 인출하려면 조기인출 동의가 필요합니다.", LogLevel.WARN),
    INSUFFICIENT_WITHDRAWAL_AMOUNT(
            HttpStatus.BAD_REQUEST, "계좌 잔액보다 많은 금액을 인출할 수 없습니다.", LogLevel.WARN),
    WITHDRAWAL_PROCESSING_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "인출 처리에 실패했습니다.", LogLevel.ERROR),
    ACCOUNT_CLOSURE_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌 해지 신청을 찾을 수 없습니다.", LogLevel.WARN),
    ACCOUNT_CLOSURE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "계좌를 해지할 수 없는 상태입니다.", LogLevel.WARN),
    ACCOUNT_CLOSURE_STATE_CONFLICT(HttpStatus.CONFLICT, "계좌 상태가 변경되어 처리할 수 없습니다.", LogLevel.WARN),
    ACCOUNT_CLOSURE_PROCESSING_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 해지 처리에 실패했습니다.", LogLevel.ERROR),
    SYSTEM_CLOCK_NOT_INITIALIZED(
            HttpStatus.INTERNAL_SERVER_ERROR, "업무시각이 초기화되지 않았습니다.", LogLevel.ERROR),
    SYSTEM_CLOCK_UPDATE_CONFLICT(HttpStatus.CONFLICT, "다른 관리자가 업무시각을 먼저 변경했습니다.", LogLevel.WARN),

    // SellOrder
    SELL_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "매도 주문을 찾을 수 없습니다.", LogLevel.WARN),
    SELL_ORDER_QTY_EXCEEDED(HttpStatus.BAD_REQUEST, "매도 가능 수량을 초과했습니다.", LogLevel.WARN),
    SELL_ORDER_CONCURRENT_CONFLICT(HttpStatus.BAD_REQUEST, "다른 요청이 먼저 처리되었습니다.", LogLevel.WARN),
    SELL_ORDER_ACCOUNT_NOT_FOUND(HttpStatus.BAD_REQUEST, "계좌 한도 정보를 찾을 수 없습니다.", LogLevel.WARN),
    SELL_ORDER_CUSTOMER_NOT_FOUND(HttpStatus.BAD_REQUEST, "고객 정보를 확인할 수 없습니다.", LogLevel.WARN),
    SELL_ORDER_RESULT_EMPTY(HttpStatus.INTERNAL_SERVER_ERROR, "매도 주문 결과가 없습니다.", LogLevel.WARN),

    // KIS / 환율
    EXCHANGE_RATE_NOT_FOUND(HttpStatus.BAD_GATEWAY, "환율 정보를 찾을 수 없습니다.", LogLevel.WARN),
    KIS_PRICE_NOT_FOUND(HttpStatus.BAD_GATEWAY, "전일종가를 조회할 수 없습니다.", LogLevel.WARN),
    KIS_TOKEN_ISSUE(HttpStatus.BAD_GATEWAY, "KIS 토큰 발급에 실패하였습니다.", LogLevel.ERROR),
    UNSUPPORTED_EXCHANGE(HttpStatus.INTERNAL_SERVER_ERROR, "지원하지 않는 거래소입니다.", LogLevel.WARN),

    // Admin
    ADMIN_NOT_FOUND(HttpStatus.NOT_FOUND, "관리자를 찾을 수 없습니다.", LogLevel.WARN),
    ADMIN_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다.", LogLevel.WARN),
    ADMIN_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.", LogLevel.WARN),
    ADMIN_TOKEN_SUBJECT_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰 정보입니다.", LogLevel.WARN),
    ADMIN_REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "refresh_token 쿠키가 없습니다.", LogLevel.WARN),

    // AuditLog
    AUDIT_LOG_ACTOR_NOT_FOUND(
            HttpStatus.BAD_REQUEST, "감사 로그를 위한 관리자 정보를 찾을 수 없습니다.", LogLevel.WARN),
    AUDIT_LOG_INSERT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "감사 로그 저장에 실패했습니다.", LogLevel.ERROR);

    private final HttpStatus status;
    private final String message;
    private final LogLevel logLevel;
}
