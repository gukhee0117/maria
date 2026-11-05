package com.app.maria.global.response;

/**
 * 고객 목록 API의 page(0부터)·size 입력을 보정한다. size는 1~100, page는 0 이상이고 offset은 int 오버플로 없이 long으로 계산한다.
 */
public record PageParam(int page, int size) {

    private static final int MAX_SIZE = 100;

    public static PageParam of(int page, int size) {
        return new PageParam(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }

    public long offset() {
        return (long) page * size;
    }
}
