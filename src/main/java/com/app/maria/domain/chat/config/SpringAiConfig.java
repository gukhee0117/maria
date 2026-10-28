package com.app.maria.domain.chat.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class SpringAiConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * 끼루 챗봇 전용 ChatClient.
     * 시스템 프롬프트(kkiru/system-prompt.txt)와 RAG 어드바이저가 기본 적용됩니다.
     *
     * <p>다른 AI 기능(브리핑, 뉴스, 경제 채팅방 등)을 구현할 때는 이 빈을 주입받지 말고,
     * {@link ChatModel}을 주입받아 {@code ChatClient.builder(chatModel)}로 기능별 빈을 별도로 만드세요.
     */
    @Bean("kkiruChatClient")
    public ChatClient kkiruChatClient(ChatModel chatModel, VectorStore vectorStore) throws IOException {
        ClassPathResource promptResource = new ClassPathResource("kkiru/system-prompt.txt");
        String systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);

        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder().topK(4).build())
                                .build())
                .build();
    }
}
