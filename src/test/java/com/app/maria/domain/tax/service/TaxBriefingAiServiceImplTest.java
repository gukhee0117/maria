package com.app.maria.domain.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

@ExtendWith(MockitoExtension.class)
class TaxBriefingAiServiceImplTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec responseSpec;
    @Mock private TaxBriefingPromptFactory promptFactory;
    @Mock private CustomerTaxBriefingResponseDTO briefing;

    private TaxBriefingAiService aiService;

    @BeforeEach
    void setUp() {
        aiService = new TaxBriefingAiServiceImpl(chatClient, promptFactory);
    }

    @Test
    void 계산_결과를_입력문으로_변환해_LLM의_설명을_반환한다() {
        String userPrompt = "현재 예상 감면액: 200000원";
        String explanation = "현재 예상 감면액은 200000원이에요. 실제 신고 세액은 달라질 수 있어요.";

        when(promptFactory.create(briefing)).thenReturn(userPrompt);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(userPrompt)).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(explanation);

        String result = aiService.generateExplanation(briefing);

        assertThat(result).isEqualTo(explanation);
        verify(promptFactory).create(briefing);
        verify(requestSpec).user(userPrompt);
        verify(responseSpec).content();
    }
}
