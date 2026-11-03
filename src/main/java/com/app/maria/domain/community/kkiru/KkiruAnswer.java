package com.app.maria.domain.community.kkiru;

import java.util.List;
import java.util.Map;

/**
 * 끼루 답변 한 개. sentences가 답의 문장이고, evidence는 이번 질문에서 모은 근거 전체(id → 근거)다. 문장 속 숫자가 근거에 있는지 확인하는 데 쓰고,
 * 화면의 근거 칩 이름·링크도 여기서 나온다.
 */
public record KkiruAnswer(List<KkiruSentence> sentences, Map<String, Evidence> evidence) {}
