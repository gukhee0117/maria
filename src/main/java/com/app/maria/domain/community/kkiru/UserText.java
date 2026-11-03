package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.service.KkiruMention;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 이용자가 쓴 글을 LLM에 넣기 전에 다듬는다(설계 후속 문서 2절): 본문 속 {@code @끼루}·제어 문자·줄바꿈을 지우고 길면 자른다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class UserText {

    static String clean(String text, int maxLength) {
        String stripped = KkiruMention.strip(text).replaceAll("\\p{Cntrl}+", " ").trim();
        return stripped.codePointCount(0, stripped.length()) > maxLength
                ? stripped.substring(0, stripped.offsetByCodePoints(0, maxLength))
                : stripped;
    }
}
