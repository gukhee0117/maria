package com.app.maria.domain.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private TaxBriefingResponseValidator responseValidator;
    @Mock private CustomerTaxBriefingResponseDTO briefing;

    private TaxBriefingAiService aiService;

    @BeforeEach
    void setUp() {
        aiService = new TaxBriefingAiServiceImpl(chatClient, promptFactory, responseValidator);
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
        when(responseValidator.isValid(explanation, briefing)).thenReturn(true);

        String result = aiService.generateExplanation(briefing);

        assertThat(result).isEqualTo(explanation);
        verify(promptFactory).create(briefing);
        verify(requestSpec).user(userPrompt);
        verify(responseSpec).content();
        verify(responseValidator).isValid(explanation, briefing);
    }

    @Test
    void LLM_응답이_검증에_실패하면_설명을_반환하지_않는다() {
        String userPrompt = "현재 예상 감면액: 200000원";
        String invalidExplanation = "지금 매도하면 300000원을 절약할 수 있어요.";

        when(promptFactory.create(briefing)).thenReturn(userPrompt);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(userPrompt)).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(invalidExplanation);
        when(responseValidator.isValid(invalidExplanation, briefing)).thenReturn(false);

        String result = aiService.generateExplanation(briefing);

        assertThat(result).isNull();
        verify(responseValidator).isValid(invalidExplanation, briefing);
    }

    @Test
    void LLM_호출에_실패하면_설명을_반환하지_않고_계산_흐름을_보호한다() {
        String userPrompt = "현재 예상 감면액: 200000원";

        when(promptFactory.create(briefing)).thenReturn(userPrompt);
        when(chatClient.prompt()).thenThrow(new IllegalStateException("OpenAI 연결 실패"));

        String result = aiService.generateExplanation(briefing);

        assertThat(result).isNull();
        verifyNoInteractions(responseValidator);
    }
}
