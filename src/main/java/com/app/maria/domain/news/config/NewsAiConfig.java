package com.app.maria.domain.news.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class NewsAiConfig {
    public static final int SUMMARY_MAX_TOKENS = 700;

    /**
     * Keeps direct test and integration setup compatible with the original two-argument factory.
     */
    public ChatClient newsChatClient(ChatModel model, NewsProperties properties) {
        return newsChatClient(
                model,
                properties,
                System.getenv().getOrDefault("OPENAI_API_KEY", ""),
                "https://api.openai.com");
    }

    @Bean("newsChatClient")
    public ChatClient newsChatClient(
            ChatModel model,
            NewsProperties properties,
            @Value("${OPENAI_API_KEY:}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl) {
        // No financial tools, RAG advisors or shared conversation memory.
        ChatModel newsModel = model;
        if (model instanceof OpenAiChatModel openAiChatModel && !apiKey.isBlank()) {
            var connector =
                    new ReactorClientHttpConnector(
                            HttpClient.create()
                                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                                    .responseTimeout(Duration.ofSeconds(20)));
            var api =
                    OpenAiApi.builder()
                            .baseUrl(baseUrl)
                            .apiKey(apiKey)
                            .webClientBuilder(WebClient.builder().clientConnector(connector))
                            .build();
            newsModel =
                    openAiChatModel
                            .mutate()
                            .openAiApi(api)
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .build();
        }
        return ChatClient.builder(newsModel)
                .defaultOptions(
                        ChatOptions.builder()
                                .model(properties.getModel())
                                .temperature(0.0)
                                .maxTokens(SUMMARY_MAX_TOKENS)
                                .build())
                .build();
    }
}
