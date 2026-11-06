package com.app.maria.domain.news.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NewsAiConfig {
    @Bean("newsChatClient")
    public ChatClient newsChatClient(ChatModel model, NewsProperties properties) {
        // No financial tools, RAG advisors or shared conversation memory.
        return ChatClient.builder(model)
                .defaultOptions(
                        ChatOptions.builder()
                                .model(properties.getModel())
                                .temperature(0.0)
                                .maxTokens(700)
                                .build())
                .build();
    }
}
