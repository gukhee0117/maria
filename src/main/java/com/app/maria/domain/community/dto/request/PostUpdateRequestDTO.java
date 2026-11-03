package com.app.maria.domain.community.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 글 수정. 종목은 바꿀 수 없다. */
public record PostUpdateRequestDTO(
        @NotNull(message = "내용을 입력해 주세요.") @Size(max = 2000, message = "내용이 너무 깁니다.")
                String content) {}
