package com.app.maria.global.exception;

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
 * 응답을 못 만들 정도로 진짜 심각한 경우에만 ERROR를 쓴다 (예: DB 연결이 끊겼다, 외부 API가 통째로 죽었다).
 */
@Getter
@RequiredArgsConstructor
public enum ErrorType {
    // 고객 로그인 (아이디 존재 여부와 비밀번호 오류를 구분해 노출하지 않는다.)
    CUSTOMER_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.", LogLevel.WARN),
    // 회원가입 (이미 가입됨과 정보 불일치를 구분해 노출하지 않는다.)
    CUSTOMER_SIGNUP_FAILED(HttpStatus.BAD_REQUEST, "가입 정보를 확인해 주세요.", LogLevel.WARN),
    CUSTOMER_LOGIN_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.", LogLevel.WARN),
    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "고객 정보를 찾을 수 없습니다.", LogLevel.WARN),

    // 계좌
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌 정보를 찾을 수 없습니다.", LogLevel.WARN),
    ACCOUNT_CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "고객 정보를 찾을 수 없습니다.", LogLevel.WARN),
    ACCOUNT_CUSTOMER_IDENTITY_NOT_FOUND(
            HttpStatus.INTERNAL_SERVER_ERROR, "고객 식별 정보를 찾을 수 없습니다.", LogLevel.ERROR),
    ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 계좌가 존재합니다.", LogLevel.WARN),
    ACCOUNT_APPLICATION_PERIOD_CLOSED(HttpStatus.BAD_REQUEST, "RIA 계좌 신청 기간이 아닙니다.", LogLevel.WARN),
    ACCOUNT_LIMIT_REQUIRED(HttpStatus.BAD_REQUEST, "계좌 한도를 입력해야 합니다.", LogLevel.WARN),
    ACCOUNT_LIMIT_BELOW_MINIMUM(HttpStatus.BAD_REQUEST, "계좌 한도는 1원 이상이어야 합니다.", LogLevel.WARN),
    ACCOUNT_LIMIT_ABOVE_MAXIMUM(
            HttpStatus.BAD_REQUEST, "계좌 한도는 50000000원 이하여야 합니다.", LogLevel.WARN),
    ACCOUNT_LIMIT_NOT_WHOLE_WON(HttpStatus.BAD_REQUEST, "계좌 한도는 원 단위로 입력해야 합니다.", LogLevel.WARN),
    ACCOUNT_NO_LIMIT_AVAILABLE(HttpStatus.BAD_REQUEST, "설정 가능한 RIA 계좌 한도가 없습니다.", LogLevel.WARN),
    ACCOUNT_LIMIT_EXCEEDS_AVAILABLE(HttpStatus.BAD_REQUEST, "신청 가능한 계좌 한도를 초과했습니다.", LogLevel.WARN),
    ACCOUNT_LIMIT_BELOW_USED_AMOUNT(
            HttpStatus.BAD_REQUEST, "계좌 한도를 이미 사용한 금액보다 낮출 수 없습니다.", LogLevel.WARN),
    ACCOUNT_LIMIT_UNCHANGED(HttpStatus.BAD_REQUEST, "변경할 한도가 기존 한도와 같습니다.", LogLevel.WARN),
    ACCOUNT_STATE_NOT_ALLOWED(HttpStatus.CONFLICT, "현재 계좌 상태에서는 요청을 처리할 수 없습니다.", LogLevel.WARN),
    ACCOUNT_CONCURRENT_MODIFICATION(
            HttpStatus.CONFLICT, "다른 요청으로 계좌 정보가 먼저 변경되었습니다.", LogLevel.WARN),
    ACCOUNT_APPLICATION_SAVE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 신청 저장에 실패했습니다.", LogLevel.ERROR),
    ACCOUNT_AMOUNT_UPDATE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 금액 변경에 실패했습니다.", LogLevel.ERROR),
    ACCOUNT_NUMBER_GENERATION_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌번호 생성에 실패했습니다.", LogLevel.ERROR),
    ACCOUNT_STATUS_LOG_SAVE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 상태 이력 저장에 실패했습니다.", LogLevel.ERROR),
    ACCOUNT_BENEFIT_LOG_SAVE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 혜택 이력 저장에 실패했습니다.", LogLevel.ERROR),
    ACCOUNT_STATE_UPDATE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 상태 변경 결과가 올바르지 않습니다.", LogLevel.ERROR),
    ACCOUNT_BENEFIT_UPDATE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 혜택 변경 결과가 올바르지 않습니다.", LogLevel.ERROR),

    // 세금
    TAX_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "유효한 세액 규칙을 찾지 못했습니다.", LogLevel.WARN),
    TAX_FINAL_REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 확정신고된 계좌입니다.", LogLevel.WARN),
    TAX_EARLY_WITHDRAWAL_CLAWBACK_ALREADY_EXISTS(
            HttpStatus.CONFLICT, "이미 조기인출 정정이 처리된 계좌입니다.", LogLevel.WARN),
    // 증빙서류
    DOCUMENT_ISSUE_NOT_OPEN(HttpStatus.CONFLICT, "아직 증빙서류를 발급할 수 있는 기간이 아닙니다.", LogLevel.WARN),
    DOCUMENT_FINAL_REPORT_NOT_FOUND(
            HttpStatus.CONFLICT, "확정된 세액 계산이 없어 증빙서류를 발급할 수 없습니다.", LogLevel.WARN),
    DOCUMENT_BENEFIT_CANCELED(
            HttpStatus.CONFLICT, "세제혜택이 취소된 계좌는 증빙서류를 발급할 수 없습니다.", LogLevel.WARN),
    DOCUMENT_BASIS_MISMATCH(
            HttpStatus.CONFLICT, "저장된 세액과 현재 계산 결과가 달라 증빙서류를 발급할 수 없습니다.", LogLevel.WARN),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "증빙서류를 찾을 수 없습니다.", LogLevel.WARN),
    DOCUMENT_REVOKED(HttpStatus.CONFLICT, "취소된 증빙서류는 다운로드할 수 없습니다.", LogLevel.WARN),
    DOCUMENT_FILE_CORRUPTED(
            HttpStatus.INTERNAL_SERVER_ERROR, "증빙서류 파일 검증에 실패했습니다.", LogLevel.ERROR),
    DOCUMENT_RENDER_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "증빙서류 PDF 생성에 실패했습니다.", LogLevel.ERROR),
    DOCUMENT_ISSUE_NO_GENERATION_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "증빙서류 발급번호 생성에 실패했습니다.", LogLevel.ERROR),
    // 인출 예외 처리
    WITHDRAWAL_NOT_FOUND(HttpStatus.NOT_FOUND, "인출 내역을 찾을 수 없습니다.", LogLevel.WARN),
    INVALID_WITHDRAWAL_AMOUNT(HttpStatus.BAD_REQUEST, "인출 금액은 0원보다 커야 합니다.", LogLevel.WARN),
    WITHDRAWAL_DESTINATION_ACCOUNT_INACTIVE(
            HttpStatus.BAD_REQUEST, "활성 상태의 일반계좌로만 인출할 수 있습니다.", LogLevel.WARN),
    GENERAL_ACCOUNT_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "일반계좌를 확인할 수 없습니다.", LogLevel.WARN),
    ACCOUNT_STATUS_NOT_WITHDRAWABLE(HttpStatus.CONFLICT, "현재 계좌 상태에서는 인출할 수 없습니다.", LogLevel.WARN),
    EARLY_WITHDRAWAL_CONSENT_REQUIRED(
            HttpStatus.BAD_REQUEST, "미경과 원금을 인출하려면 조기인출 동의가 필요합니다.", LogLevel.WARN),
    INSUFFICIENT_WITHDRAWAL_AMOUNT(
            HttpStatus.BAD_REQUEST, "계좌 잔액보다 많은 금액을 인출할 수 없습니다.", LogLevel.WARN),
    WITHDRAWAL_BLOCKED_BY_PROVISIONAL(
            HttpStatus.BAD_REQUEST, "미확정 정산 중인 금액이 있어 인출 가능 금액이 부족합니다.", LogLevel.WARN),
    WITHDRAWAL_SOURCE_AMOUNT_INCONSISTENT(
            HttpStatus.INTERNAL_SERVER_ERROR, "인출 가능 금액을 계산하는 중 오류가 발생했습니다.", LogLevel.ERROR),
    WITHDRAWAL_PROCESSING_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "인출 처리에 실패했습니다.", LogLevel.ERROR),
    GENERAL_ACCOUNT_API_INVALID_RESPONSE(
            HttpStatus.BAD_GATEWAY, "일반계좌 확인 응답을 처리할 수 없습니다.", LogLevel.ERROR),
    GENERAL_ACCOUNT_API_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "일반계좌 확인 서비스에 일시적으로 연결할 수 없습니다.", LogLevel.ERROR),
    MYDATA_API_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "myData 응답을 처리할 수 없습니다.", LogLevel.ERROR),
    MYDATA_API_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "myData 서비스에 일시적으로 연결할 수 없습니다.", LogLevel.ERROR),

    // 계좌 해지 예외 처리
    ACCOUNT_CLOSURE_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌 해지 신청을 찾을 수 없습니다.", LogLevel.WARN),
    ACCOUNT_CLOSURE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "계좌를 해지할 수 없는 상태입니다.", LogLevel.WARN),
    ACCOUNT_CLOSURE_STATE_CONFLICT(HttpStatus.CONFLICT, "계좌 상태가 변경되어 처리할 수 없습니다.", LogLevel.WARN),
    ACCOUNT_CLOSURE_PROCESSING_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "계좌 해지 처리에 실패했습니다.", LogLevel.ERROR),
    // Clock 예외 처리
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
    EXCHANGE_RATE_API_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "환율 API 호출에 실패했습니다.", LogLevel.ERROR),

    // Settlement / 가환전
    SETTLEMENT_INVALID_REQUEST(HttpStatus.BAD_REQUEST, "정산 요청 정보가 올바르지 않습니다.", LogLevel.WARN),
    SETTLEMENT_CALCULATION_FAILED(HttpStatus.BAD_REQUEST, "정산 금액을 계산할 수 없습니다.", LogLevel.WARN),
    SETTLEMENT_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "정산 배치를 찾을 수 없습니다.", LogLevel.WARN),
    SETTLEMENT_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "정산 항목을 찾을 수 없습니다.", LogLevel.WARN),
    KRW_EXCHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "환전 정보를 찾을 수 없습니다.", LogLevel.WARN),
    SETTLEMENT_ACCOUNT_MISMATCH(HttpStatus.CONFLICT, "환전과 정산 대상 계좌가 일치하지 않습니다.", LogLevel.WARN),
    SETTLEMENT_STATE_CONFLICT(HttpStatus.CONFLICT, "정산 상태가 변경되어 처리할 수 없습니다.", LogLevel.WARN),
    SETTLEMENT_RETRY_NO_FAILED_ITEM(HttpStatus.CONFLICT, "재처리할 실패 정산 항목이 없습니다.", LogLevel.WARN),
    SETTLEMENT_BATCH_LOCK_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "정산 배치 실행 잠금을 얻지 못했습니다.", LogLevel.ERROR),
    SETTLEMENT_HOLIDAY_CALENDAR_UNAVAILABLE(
            HttpStatus.INTERNAL_SERVER_ERROR, "정산 휴일 정보를 사용할 수 없습니다.", LogLevel.ERROR),
    PROVISIONAL_EXCHANGE_SAVE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "가환전 저장에 실패했습니다.", LogLevel.ERROR),

    // Admin
    ADMIN_NOT_FOUND(HttpStatus.NOT_FOUND, "관리자를 찾을 수 없습니다.", LogLevel.WARN),
    ADMIN_LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다.", LogLevel.WARN),
    ADMIN_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.", LogLevel.WARN),
    ADMIN_TOKEN_SUBJECT_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰 정보입니다.", LogLevel.WARN),
    ADMIN_REFRESH_TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "refresh_token 쿠키가 없습니다.", LogLevel.WARN),

    // AuditLog
    AUDIT_LOG_ACTOR_NOT_FOUND(
            HttpStatus.BAD_REQUEST, "감사 로그를 위한 관리자 정보를 찾을 수 없습니다.", LogLevel.WARN),
    AUDIT_LOG_INSERT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "감사 로그 저장에 실패했습니다.", LogLevel.ERROR),

    // TargetProduct(G2)
    TARGET_PRODUCT_FUND_NOT_FOUND(HttpStatus.BAD_GATEWAY, "펀드 정보를 조회할 수 없습니다.", LogLevel.ERROR),

    // Domestic(D1/D2)
    DOMESTIC_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "종목 정보를 찾을 수 없습니다.", LogLevel.WARN),
    DOMESTIC_INVESTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "계좌를 찾을 수 없습니다.", LogLevel.WARN),

    // Foreign product / Member
    FOREIGN_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "종목 정보를 찾을 수 없습니다.", LogLevel.WARN),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 정보를 찾을 수 없습니다.", LogLevel.WARN),

    // Inbound(B1)
    REGISTRABLE_STOCK_NOT_FOUND(
            HttpStatus.BAD_GATEWAY, "등록가능 보유수량 정보를 조회할 수 없습니다.", LogLevel.ERROR),

    // 고객 입고 조회 (다른 고객의 입고 조회 시 존재 여부를 노출하지 않는다.)
    INBOUND_NOT_FOUND(HttpStatus.NOT_FOUND, "입고 내역을 찾을 수 없습니다.", LogLevel.WARN),

    // 고객 입고 신청
    CUSTOMER_INBOUND_QTY_ZERO(HttpStatus.BAD_REQUEST, "입고 가능한 수량이 없습니다.", LogLevel.WARN),
    CUSTOMER_REGISTRABLE_STOCK_NOT_FOUND(
            HttpStatus.BAD_REQUEST, "선택한 계좌·종목의 적격 주식 정보를 찾을 수 없습니다.", LogLevel.WARN),
    SOURCE_ACCOUNT_INVALID(HttpStatus.BAD_REQUEST, "출처 일반계좌를 확인할 수 없습니다.", LogLevel.WARN),

    // 입고 신청 — lot 배분 불가·무결성 위반
    INBOUND_LOT_ORIGIN_UNRESOLVABLE(
            HttpStatus.CONFLICT,
            "기존 입고 이력의 원본 계좌를 확인할 수 없어 신청을 처리할 수 없습니다. 관리자에게 문의해 주세요.",
            LogLevel.WARN),
    INBOUND_ALLOCATION_INTEGRITY_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR, "입고 수량 배분 중 오류가 발생했습니다.", LogLevel.ERROR),

    // 출고
    OUTBOUND_NO_AVAILABLE_QTY(HttpStatus.BAD_REQUEST, "출고 가능한 수량이 없습니다.", LogLevel.WARN);

    private final HttpStatus status;
    private final String message;
    private final LogLevel logLevel;
}
