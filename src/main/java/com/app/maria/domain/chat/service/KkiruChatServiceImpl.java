package com.app.maria.domain.chat.service;

import com.app.maria.domain.chat.dto.request.ChatRequestDTO;
import com.app.maria.domain.chat.dto.request.MessageDTO;
import com.app.maria.domain.chat.tool.AccountChatTool;
import com.app.maria.global.clock.service.BusinessClockService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final AccountChatTool accountChatTool;

    public KkiruChatServiceImpl(
            @Qualifier("kkiruChatClient") ChatClient chatClient,
            BusinessClockService businessClockService,
            AccountChatTool accountChatTool) {
        this.chatClient = chatClient;
        this.businessClockService = businessClockService;
        this.accountChatTool = accountChatTool;
    }

    @Override
    public Flux<String> stream(ChatRequestDTO request, Long customerId) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("오늘 날짜: " + businessClockService.now().toLocalDate()));
        request.getMessages().stream().map(this::toMessage).forEach(messages::add);

        return chatClient
                .prompt()
                .messages(messages)
                .user(request.getQuestion())
                .tools(accountChatTool)
                .toolContext(buildCtx(customerId))
                .stream()
                .content();
    }

    /** Map.of()는 null 값을 허용하지 않으므로 HashMap 사용 (비로그인 시 customerId = null). */
    private static Map<String, Object> buildCtx(Long customerId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("customerId", customerId);
        return ctx;
    }

    private Message toMessage(MessageDTO dto) {
        return "user".equals(dto.getRole())
                ? new UserMessage(dto.getContent())
                : new AssistantMessage(dto.getContent());
    }
}
