package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KkiruQuestionClassifierTest {
    private final KkiruQuestionClassifier classifier = new KkiruQuestionClassifier();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@끼루 이거 지금 사도 돼요?",
                "@끼루 지금 사도돼요",
                "@끼루 이 종목 오를까요",
                "@끼루 하이닉스 전망 알려줘",
                "@끼루 뭐 추천해줘",
                "@끼루 목표가가 얼마예요",
                "@끼루 언제 팔아야 해요",
                "@끼루 사 도 될 까요 (공백으로 쪼갬)",
                "@끼루 이 종목 지금 담아도 될까요"
            })
    @DisplayName("매수·매도 판단, 전망, 추천, 목표가를 묻는 질문은 범위 밖이다")
    void outOfScope(String text) {
        assertThat(classifier.isOutOfScope(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@끼루 국내 주식 판 돈도 1년 묶이나요?",
                "@끼루 매도 결제일이 언제예요?",
                "@끼루 매수한 주식은 RIA에서 팔 수 있나요?",
                "@끼루 HBM 증설 기사 나왔던데 공시한 내용도 있나요?",
                "@끼루 이 종목 위험등급이 뭐예요",
                "@끼루 양도소득 기본공제가 얼마예요"
            })
    @DisplayName("제도·사실 질문은 범위 안이다 (매수·매도·수익 같은 단어 하나로 막지 않는다)")
    void inScope(String text) {
        assertThat(classifier.isOutOfScope(text)).isFalse();
    }
}
