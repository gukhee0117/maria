package com.app.maria.domain.community.type;

/** 본문에 @끼루가 있을 때 호출이 어디까지 진행됐는지. 호출이 없으면 저장 값은 NULL이다. */
public enum KkiruStatus {
    PENDING,
    ANSWERED,
    NO_SOURCE,
    OUT_OF_SCOPE,
    FAILED;

    public static boolean canMove(KkiruStatus from, KkiruStatus to) {
        if (to == null) {
            return false;
        }
        if (from == null) {
            return to == PENDING || to == OUT_OF_SCOPE;
        }
        return switch (from) {
            case PENDING -> to == ANSWERED || to == NO_SOURCE || to == FAILED;
            case FAILED -> to == PENDING;
            default -> false;
        };
    }
}
