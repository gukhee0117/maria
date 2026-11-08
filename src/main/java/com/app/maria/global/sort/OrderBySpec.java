package com.app.maria.global.sort;

import java.util.Map;

/**
 * 목록 API 의 정렬 요청(sort 키 + direction)을 안전한 ORDER BY 문자열로 바꾼다.
 *
 * <p>허용 목록(키 → SQL 식)에 없는 키는 무시하고 기본 정렬을 쓴다. MyBatis 의 {@code ${orderBy}} 로 넣어도 SQL 주입이 생기지 않도록 이
 * 클래스가 만든 문자열만 넘길 것.
 */
public final class OrderBySpec {

    private OrderBySpec() {}

    /**
     * @param allowed 화면이 보내는 정렬 키 → 실제 SQL 식 (정해진 값만)
     * @param sort 요청의 정렬 키 (없거나 허용 목록에 없으면 기본 정렬)
     * @param direction "desc" 면 내림차순, 그 외는 오름차순
     * @param defaultOrderBy 기본 정렬 (동률일 때 순서를 고정하는 보조 정렬로도 붙는다)
     */
    public static String resolve(
            Map<String, String> allowed, String sort, String direction, String defaultOrderBy) {
        if (sort == null || !allowed.containsKey(sort)) {
            return defaultOrderBy;
        }
        String dir = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";
        return allowed.get(sort) + " " + dir + ", " + defaultOrderBy;
    }
}
