package com.app.maria.domain.community.kkiru;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 질문 단어 매칭(설계 4.2). 질문을 정규화한 뒤 단어가 하나라도 들어 있는지 본다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class QuestionWords {

    /** NFKC → 소문자 → 글자·숫자 아닌 것(공백·특수문자) 제거. {@code @끼루}는 글자만 남아도 "끼루"라 따로 지운다. */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("@끼루", "")
                .replaceAll("[^0-9a-z가-힣]", "");
    }

    static boolean containsAny(KkiruQuestion question, List<String> words) {
        String normalized = normalize(question.text());
        return words.stream().anyMatch(normalized::contains);
    }
}
