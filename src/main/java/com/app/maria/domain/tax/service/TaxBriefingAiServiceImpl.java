package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TaxBriefingAiServiceImpl implements TaxBriefingAiService {

    private final ChatClient chatClient;
    private final TaxBriefingPromptFactory promptFactory;
    private final TaxBriefingResponseValidator responseValidator;

    public TaxBriefingAiServiceImpl(
            @Qualifier("taxBriefingChatClient") ChatClient chatClient,
            TaxBriefingPromptFactory promptFactory,
            TaxBriefingResponseValidator responseValidator) {
        this.chatClient = chatClient;
        this.promptFactory = promptFactory;
        this.responseValidator = responseValidator;
    }

    @Override
    public String generateExplanation(CustomerTaxBriefingResponseDTO briefing) {
        String userPrompt = promptFactory.create(briefing);

        try {
            String explanation = chatClient.prompt().user(userPrompt).call().content();
            if (!responseValidator.isValid(explanation, briefing)) {
                log.warn("절세 브리핑 AI 응답이 검증을 통과하지 못했습니다.");
                return null;
            }
            return explanation;
        } catch (RuntimeException e) {
            log.warn("절세 브리핑 AI 설명 생성에 실패했습니다. 원인: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
