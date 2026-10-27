package com.app.maria.domain.chat.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestDTOTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void question이_blank면_유효성검사_실패() {
        ChatRequestDTO dto = new ChatRequestDTO(List.of(), "");
        Set<ConstraintViolation<ChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void question이_1000자_초과면_유효성검사_실패() {
        ChatRequestDTO dto = new ChatRequestDTO(List.of(), "a".repeat(1001));
        Set<ConstraintViolation<ChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void messages가_null이면_유효성검사_실패() {
        ChatRequestDTO dto = new ChatRequestDTO(null, "RIA가 뭔가요?");
        Set<ConstraintViolation<ChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void MessageDTO_role이_잘못된_값이면_유효성검사_실패() {
        MessageDTO badRole = new MessageDTO("system", "내용");
        ChatRequestDTO dto = new ChatRequestDTO(List.of(badRole), "질문");
        Set<ConstraintViolation<ChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void 정상_요청은_유효성검사_통과() {
        ChatRequestDTO dto =
                new ChatRequestDTO(
                        List.of(
                                new MessageDTO("user", "안녕"),
                                new MessageDTO("assistant", "안녕하세요")),
                        "RIA 한도가 얼마예요?");
        Set<ConstraintViolation<ChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void messages가_빈_리스트면_유효성검사_통과() {
        ChatRequestDTO dto = new ChatRequestDTO(List.of(), "RIA가 뭔가요?");
        Set<ConstraintViolation<ChatRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }
}
