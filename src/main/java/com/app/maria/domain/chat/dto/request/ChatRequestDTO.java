package com.app.maria.domain.chat.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDTO {

    @NotNull
    @Valid
    @Size(max = 20, message = "대화 이력은 최대 20개까지 허용됩니다.")
    private List<MessageDTO> messages;

    @NotBlank
    @Size(max = 1000)
    private String question;
}
