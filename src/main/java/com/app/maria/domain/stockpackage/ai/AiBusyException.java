package com.app.maria.domain.stockpackage.ai;

/** 동시에 보낼 수 있는 AI 호출이 모두 쓰이는 중이라 새 호출을 받지 못했다. 잠시 뒤에 다시 하면 된다. */
public class AiBusyException extends RuntimeException {
    public AiBusyException() {
        super("AI 호출이 모두 쓰이는 중이다");
    }
}
