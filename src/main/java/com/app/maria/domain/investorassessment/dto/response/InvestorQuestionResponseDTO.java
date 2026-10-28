package com.app.maria.domain.investorassessment.dto.response;

import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 설문 문항 하나. 보기의 배점은 화면이 쓸 일이 없어서 내려주지 않는다. */
@Getter
@RequiredArgsConstructor
public class InvestorQuestionResponseDTO {
    /** 제출 요청에서 이 문항의 응답을 담는 필드 이름. */
    private final String answerKey;

    private final String category;
    private final String text;
    private final boolean multiple;
    private final List<Choice> choices;

    @Getter
    @RequiredArgsConstructor
    public static class Choice {
        /** 제출할 때 보내는 값(enum 상수 이름). */
        private final String value;

        private final String label;
    }
}
