package com.app.maria.domain.tax.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class TaxBriefingAiConfig {

    @Bean("taxBriefingChatClient")
    public ChatClient taxBriefingChatClient(ChatModel chatModel) throws IOException {
        ClassPathResource promptResource =
                new ClassPathResource("tax/briefing-system-prompt.txt");
        String systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);

        return ChatClient.builder(chatModel).defaultSystem(systemPrompt).build();
    }
}
