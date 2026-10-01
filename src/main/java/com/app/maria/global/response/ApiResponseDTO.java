package com.app.maria.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@NoArgsConstructor
@Getter
@Setter
@ToString
public class ApiResponseDTO<T> {

    private String message;
    private T data;

    // 에러가 어떤 종류인지 알려주는 값 (예: "TAX_RULE_NOT_FOUND"). AppException으로 예외를 던지는 곳만
    // 이 값이 채워짐 — Tax/SellOrder/KIS/환율/Admin/AuditLog 도메인이 여기 해당. 나머지 도메인은 아직 옛날 방식 그대로라 code가 항상 null이고,
    // null이면 응답 JSON에 아예 안 찍힘(@JsonInclude 때문) — 그래서 기존 화면들은 이 필드 추가해도 안 깨짐.
    //
    // 지금 당장 화면(JS)에서 이 code를 보고 뭘 하진 않음. 2차 React 화면 만들 때 "이 code면 이 모달 띄워라"
    // 처럼 에러 종류별로 분기하는 용도로 쓸 예정 — message(사람이 읽는 한글 문구)는 나중에 바뀔 수 있지만
    // code는 안 바뀌니까, 화면 로직은 message 말고 code로 판단해야 함.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String code;

    ApiResponseDTO(String message) {
        this.message = message;
    }

    ApiResponseDTO(String message, T data) {
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponseDTO<T> of(String message) {
        return new ApiResponseDTO<>(message);
    }

    public static <T> ApiResponseDTO<T> of(String message, T data) {
        return new ApiResponseDTO<>(message, data);
    }

    public static <T> ApiResponseDTO<T> error(String code, String message) {
        ApiResponseDTO<T> response = new ApiResponseDTO<>(message);
        response.code = code;
        return response;
    }
}
