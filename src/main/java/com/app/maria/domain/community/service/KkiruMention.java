package com.app.maria.domain.community.service;

import java.text.Normalizer;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 본문에서 {@code @끼루} 호출을 찾는다. 서버가 직접 읽고, 화면이 보낸 표시는 믿지 않는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KkiruMention {
    // 앞이 글자·숫자·밑줄·점이면 다른 말의 일부(예: 메일 주소)로 보고 호출로 세지 않는다.
    private static final Pattern MENTION = Pattern.compile("(?<![\\p{L}\\p{N}_.])@끼루");

    /** 전각 ＠도 같은 호출로 본다(NFKC). */
    public static boolean in(String text) {
        return MENTION.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).find();
    }

    /** 질문에서 {@code @끼루} 표시를 지우고 공백을 하나로 줄인다. */
    public static String strip(String text) {
        return MENTION.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .replaceAll("")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
