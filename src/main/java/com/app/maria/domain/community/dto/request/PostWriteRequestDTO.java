package com.app.maria.domain.community.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 글 쓰기. ticker가 비면 종목 없는 일반 글이다. 본문 길이(1~500자)는 서비스가 정확히 검사한다. */
public record PostWriteRequestDTO(
        @Size(max = 20, message = "종목코드가 너무 깁니다.") String ticker,
        @NotNull(message = "내용을 입력해 주세요.") @Size(max = 2000, message = "내용이 너무 깁니다.")
                String content) {}
