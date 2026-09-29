package com.app.maria.global.error;

import lombok.Getter;

/**
 * 이제 예외를 던질 땐 새 클래스를 만들 필요 없이 이거 하나만 쓰면 된다.
 *
 * <p>사용법 두 가지:
 *
 * <pre>{@code
 * // 1) 그냥 던지기 — 메시지는 ErrorType에 적어둔 고정 문구가 그대로 나감
 * throw new AppException(ErrorType.TAX_RULE_NOT_FOUND);
 *
 * // 2) 로그에 힌트를 같이 남기고 싶을 때 — 두 번째 인자(errorData)에 아무거나 넣으면 됨
 * throw new AppException(ErrorType.TAX_RULE_NOT_FOUND, accountId);
 * }</pre>
 *
 * <p><b>주의</b>: errorData는 로그에만 찍히고 사용자한테 가는 응답 메시지엔 절대 안 들어간다. "이 accountId를 사용자가 볼 메시지에 넣고 싶다"는 안
 * 됨 — ErrorType의 메시지는 고정이고, 그게 이 구조의 핵심이다(메시지를 서비스 코드마다 다르게 타이핑하지 말자는 게 원래 목적).
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorType errorType;
    private final transient Object errorData;

    public AppException(ErrorType errorType) {
        this(errorType, null);
    }

    public AppException(ErrorType errorType, Object errorData) {
        this(errorType, errorData, null);
    }

    public AppException(ErrorType errorType, Object errorData, Throwable cause) {
        super(errorType.getMessage(), cause);
        this.errorType = errorType;
        this.errorData = errorData;
    }
}
