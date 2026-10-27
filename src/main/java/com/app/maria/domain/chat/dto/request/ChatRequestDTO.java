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
    private List<MessageDTO> messages;

    @NotBlank
    @Size(max = 1000)
    private String question;
}
