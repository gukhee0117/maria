package com.app.maria.domain.community.kkiru;

import java.util.List;

/** 끼루 답변의 문장 하나와 그 문장이 기댄 근거 id들. 근거 없는 문장은 답변에 들어갈 수 없다. */
public record KkiruSentence(String text, List<String> sourceIds) {}
