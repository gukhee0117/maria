package com.app.maria.domain.community.kkiru;

import java.util.List;

/**
 * 끼루 답변의 근거를 모으는 도구. 의견은 내지 않고 공식 자료의 사실만 {@link Evidence}로 돌려준다. 도구의 순서({@code @Order})가 질문에 맞는
 * 도구를 고를 때의 우선순위다.
 */
public interface EvidenceTool {
    /** 로그·테스트용 이름. */
    String name();

    /** 종목이 있어야 쓰는 도구인지. 공시·재무·위험처럼 종목 자료를 보는 도구는 true(기본)이고, 글의 토론을 읽는 도구는 false다. */
    default boolean needsTicker() {
        return true;
    }

    /** 질문 단어와 종목 조건으로 이 도구가 후보인지. */
    boolean isRelevant(KkiruQuestion question);

    /** 근거 목록. 자료가 없으면 빈 목록이고, 외부 오류는 예외로 던진다(없음과 오류를 구분한다). */
    List<Evidence> collect(KkiruQuestion question);
}
