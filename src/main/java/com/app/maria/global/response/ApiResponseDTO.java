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

    // AppException의 ErrorType 이름을 담아 프론트가 메시지 문구가 아닌 안정적인 코드로 분기할 수 있게 한다.
    // 기존 예외 응답은 null이며 @JsonInclude에 의해 JSON에 포함되지 않는다.
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
