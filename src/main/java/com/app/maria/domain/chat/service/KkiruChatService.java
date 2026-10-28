package com.app.maria.domain.chat.service;

import com.app.maria.domain.chat.dto.request.ChatRequestDTO;
import reactor.core.publisher.Flux;

public interface KkiruChatService {
    Flux<String> stream(ChatRequestDTO request);
}
