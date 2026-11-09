package com.app.maria.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageParamTest {

    @Test
    void clampsSizeToOneThroughHundredAndPageToZeroOrMore() {
        assertThat(PageParam.of(-5, 100000)).isEqualTo(new PageParam(0, 100));
        assertThat(PageParam.of(-5, 0)).isEqualTo(new PageParam(0, 1));
        assertThat(PageParam.of(3, 20)).isEqualTo(new PageParam(3, 20));
    }

    @Test
    void offsetDoesNotOverflowInt() {
        assertThat(PageParam.of(Integer.MAX_VALUE, 100).offset())
                .isEqualTo((long) Integer.MAX_VALUE * 100);
        assertThat(PageParam.of(2, 20).offset()).isEqualTo(40L);
    }
}
