package com.app.maria.domain.community.kkiru;

/**
 * 도구가 모은 근거 하나. text는 그 자체로 읽히는 한 문장이고, LLM의 재료이면서 LLM이 실패했을 때 템플릿 답의 문장이 된다. id는 {@code 도구:키} 형식으로
 * 한 요청 안에서 겹치지 않는다. url은 출처 링크이고 없으면 null이다. userContent는 이용자가 쓴 글을 근거로 쓴 것인지 나타낸다(설계 후속 문서 2절).
 */
public record Evidence(String id, String text, String url, boolean userContent) {
    /** 공식 자료 근거(이용자 글이 아님). */
    public Evidence(String id, String text, String url) {
        this(id, text, url, false);
    }
}
