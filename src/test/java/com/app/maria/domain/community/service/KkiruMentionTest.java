package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KkiruMentionTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@끼루 공시 있나요",
                "공시 있나요 @끼루",
                "질문이요 @끼루야 알려줘",
                "＠끼루 전각 골뱅이",
                "줄바꿈\n@끼루\n질문",
                "(@끼루) 괄호 안에서도"
            })
    @DisplayName("@끼루가 있으면 호출로 본다")
    void detectsMention(String text) {
        assertThat(KkiruMention.in(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "끼루 알려줘",
                "@ 끼루 공백이 있으면 호출이 아니다",
                "abc@끼루 메일 주소처럼 붙은 것",
                "user.name@끼루",
                "@kiru",
                "@끼 루",
                ""
            })
    @DisplayName("@끼루가 없거나 다른 말의 일부면 호출이 아니다")
    void ignoresNonMention(String text) {
        assertThat(KkiruMention.in(text)).isFalse();
    }

    @Test
    @DisplayName("질문에서 @끼루 표시를 지우고 공백을 하나로 줄인다")
    void strip() {
        assertThat(KkiruMention.strip("@끼루   배당  어땠어요? ＠끼루")).isEqualTo("배당 어땠어요?");
        assertThat(KkiruMention.strip("메일 a@끼루.com 문의")).isEqualTo("메일 a@끼루.com 문의");
    }
}
