package com.app.maria.domain.chat.service;

import com.app.maria.domain.chat.dto.request.ChatRequestDTO;
import com.app.maria.domain.chat.dto.request.MessageDTO;
import com.app.maria.global.clock.service.BusinessClockService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class KkiruChatServiceImpl implements KkiruChatService {

    private final ChatClient chatClient;
    private final BusinessClockService businessClockService;

    public KkiruChatServiceImpl(
            @Qualifier("kkiruChatClient") ChatClient chatClient,
            BusinessClockService businessClockService) {
        this.chatClient = chatClient;
        this.businessClockService = businessClockService;
    }

    @Override
    public Flux<String> stream(ChatRequestDTO request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("오늘 날짜: " + businessClockService.now().toLocalDate()));
        request.getMessages().stream().map(this::toMessage).forEach(messages::add);

        return chatClient.prompt().messages(messages).user(request.getQuestion()).stream()
                .content();
    }

    private Message toMessage(MessageDTO dto) {
        return "user".equals(dto.getRole())
                ? new UserMessage(dto.getContent())
                : new AssistantMessage(dto.getContent());
    }
}
