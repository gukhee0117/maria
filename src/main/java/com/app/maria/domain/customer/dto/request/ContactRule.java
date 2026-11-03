package com.app.maria.domain.customer.dto.request;

/** 연락처 형식. 휴대폰은 하이픈이 있어도 없어도 받고, 저장할 때 010-1234-5678 모양으로 맞춘다. */
public final class ContactRule {
    public static final String PHONE_REGEX = "^01\\d-?\\d{3,4}-?\\d{4}$";
    public static final String PHONE_MESSAGE = "휴대폰 번호 형식이 올바르지 않습니다.";
    public static final String EMAIL_REGEX = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";
    public static final String EMAIL_MESSAGE = "이메일 주소 형식이 올바르지 않습니다.";

    private ContactRule() {}

    /** 숫자만 남겨 10자리면 3-3-4, 11자리면 3-4-4로 나눈다. 형식 검사를 통과한 값에만 쓴다. */
    public static String normalizePhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        int head = digits.length() == 10 ? 6 : 7;
        return digits.substring(0, 3)
                + "-"
                + digits.substring(3, head)
                + "-"
                + digits.substring(head);
    }
}
