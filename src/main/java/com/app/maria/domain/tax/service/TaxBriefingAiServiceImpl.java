package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class TaxBriefingAiServiceImpl implements TaxBriefingAiService {

    private final ChatClient chatClient;
    private final TaxBriefingPromptFactory promptFactory;

    public TaxBriefingAiServiceImpl(
            @Qualifier("taxBriefingChatClient") ChatClient chatClient,
            TaxBriefingPromptFactory promptFactory) {
        this.chatClient = chatClient;
        this.promptFactory = promptFactory;
    }

    @Override
    public String generateExplanation(CustomerTaxBriefingResponseDTO briefing) {
        String userPrompt = promptFactory.create(briefing);

        return chatClient.prompt().user(userPrompt).call().content();
    }
}
