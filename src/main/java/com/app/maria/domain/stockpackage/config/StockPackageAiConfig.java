package com.app.maria.domain.stockpackage.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class StockPackageAiConfig {

    /**
     * 주식 패키지 조건 변환 전용 ChatClient. 끼루 챗봇의 RAG·시스템 프롬프트와 분리했고, 결정적인 출력을 위해 온도를 0으로 둔다. 시스템 프롬프트는 호출할
     * 때 테마 목록을 채워 넣는다.
     */
    @Bean("stockPackageChatClient")
    public ChatClient stockPackageChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(ChatOptions.builder().temperature(0.0).maxTokens(300).build())
                .build();
    }

    /** 조건 변환 시스템 프롬프트. <<THEMES>> 자리에 테마 목록이 들어간다. */
    @Bean("stockConditionPrompt")
    public String stockConditionPrompt() throws IOException {
        return new ClassPathResource("stockpackage/condition-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /** 추천 질문 답변 시스템 프롬프트. <<FACTS>> 자리에 서버가 만든 사실 목록이 들어간다. */
    @Bean("stockRecommendationPrompt")
    public String stockRecommendationPrompt() throws IOException {
        return new ClassPathResource("stockpackage/recommendation-prompt.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
