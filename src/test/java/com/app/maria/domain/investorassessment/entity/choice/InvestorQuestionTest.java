package com.app.maria.domain.investorassessment.entity.choice;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.maria.domain.investorassessment.dto.request.InvestorAssessmentSubmitRequestDTO;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvestorQuestionTest {

    @Test
    @DisplayName("문항 answerKey는 제출 요청의 필드 이름과 정확히 일치한다")
    void answerKeysMatchRequestFields() {
        Set<String> requestFields =
                Arrays.stream(InvestorAssessmentSubmitRequestDTO.class.getDeclaredFields())
                        .map(Field::getName)
                        .collect(Collectors.toSet());
        Set<String> answerKeys =
                Arrays.stream(InvestorQuestion.values())
                        .map(InvestorQuestion::answerKey)
                        .collect(Collectors.toSet());

        assertThat(answerKeys).isEqualTo(requestFields);
    }

    @Test
    @DisplayName("문항별 최고 배점의 합이 50점 만점이다")
    void maxScoresSumTo50() {
        int sum =
                Arrays.stream(InvestorQuestion.values())
                        .mapToInt(
                                q ->
                                        q.choices().stream()
                                                .mapToInt(ScoredChoice::score)
                                                .max()
                                                .orElseThrow())
                        .sum();

        assertThat(sum).isEqualTo(50);
    }

    @Test
    @DisplayName("복수 선택 문항은 투자경험 하나뿐이다")
    void onlyExperienceIsMultiple() {
        assertThat(
                        Arrays.stream(InvestorQuestion.values())
                                .filter(InvestorQuestion::multiple)
                                .toList())
                .containsExactly(InvestorQuestion.EXPERIENCE);
    }
}
