package com.app.maria.domain.account.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.*;
import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotNull(message = "계좌 한도는 필수입니다.")
@DecimalMin(value = "1", message = "계좌 한도는 1원 이상이어야 합니다.")
@DecimalMax(value = "50000000", message = "계좌 한도는 5천만원 이하여야 합니다.")
@Digits(integer = 8, fraction = 0, message = "계좌 한도는 원 단위 정수로 입력해야 합니다.")
public @interface AccountLimit {
    String message() default "계좌 한도를 확인해 주세요.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
