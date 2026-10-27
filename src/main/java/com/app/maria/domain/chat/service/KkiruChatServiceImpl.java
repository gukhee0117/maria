package com.app.maria.domain.chat.service;

import com.app.maria.domain.chat.dto.request.ChatRequestDTO;
import com.app.maria.domain.chat.dto.request.MessageDTO;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class KkiruChatServiceImpl implements KkiruChatService {

    private final ChatClient chatClient;

    public KkiruChatServiceImpl(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Flux<String> stream(ChatRequestDTO request) {
        List<Message> history =
                request.getMessages().stream().map(this::toMessage).toList();

        return chatClient
                .prompt()
                .messages(history)
                .user(request.getQuestion())
                .stream()
                .content();
    }

    private Message toMessage(MessageDTO dto) {
        return "user".equals(dto.getRole())
                ? new UserMessage(dto.getContent())
                : new AssistantMessage(dto.getContent());
    }
}
