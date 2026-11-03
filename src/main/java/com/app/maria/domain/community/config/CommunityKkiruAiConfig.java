package com.app.maria.domain.community.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 커뮤니티 끼루 답변 전용 ChatClient. 챗봇 빈(kkiruChatClient)이나 RAG 어드바이저는 쓰지 않는다. 시스템 프롬프트는 호출마다 메시지로
 * 보낸다(KkiruAnswerWriter). 모델은 기존 설정을 따르고 여기서는 낮은 온도·짧은 답·JSON 출력만 정한다.
 */
@Configuration
public class CommunityKkiruAiConfig {
    @Bean("communityKkiruChatClient")
    public ChatClient communityKkiruChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultOptions(
                        OpenAiChatOptions.builder()
                                .temperature(0.2)
                                .maxTokens(400)
                                .responseFormat(
                                        ResponseFormat.builder()
                                                .type(ResponseFormat.Type.JSON_OBJECT)
                                                .build())
                                .build())
                .build();
    }
}
