package com.app.maria.domain.community.kkiru;

import static com.app.maria.domain.community.kkiru.OpenDartFixtures.question;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.app.maria.global.client.opendart.OpenDartClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapitalIncreaseToolTest {
    private final OpenDartClient client = OpenDartFixtures.enabledClient();
    private final CapitalIncreaseTool tool =
            new CapitalIncreaseTool(client, OpenDartFixtures.clock());

    @Test
    @DisplayName("실제 응답: 접수일(접수번호 앞 8자리)·증자방식·신주 수·자금조달 목적. 결정일·발행가는 말하지 않는다")
    void sentence() {
        when(client.list(eq("piicDecsn"), anyMap()))
                .thenReturn(OpenDartFixtures.rows("piicDecsn_hynix.json"));

        List<Evidence> evidence = tool.collect(question("000660", "유상증자 했어요?"));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).id()).isEqualTo("capital:20260710000008");
        assertThat(evidence.get(0).text())
                .isEqualTo(
                        "SK하이닉스의 유상증자 결정 공시(2026년 7월 10일 접수)는 증자방식 제3자배정증자, "
                                + "신주 보통주 17,790,000주, 자금조달 목적 시설자금 40조 231억원입니다.");
        assertThat(evidence.get(0).text()).doesNotContain("발행가").doesNotContain("결정일");
    }

    @Test
    @DisplayName("접수번호 내림차순으로 최신 2건까지만 쓴다")
    void latestTwo() {
        when(client.list(eq("piicDecsn"), anyMap()))
                .thenReturn(
                        List.of(
                                OpenDartFixtures.json(
                                        "{\"rcept_no\":\"20260101000001\",\"corp_name\":\"가\"}"),
                                OpenDartFixtures.json(
                                        "{\"rcept_no\":\"20260301000001\",\"corp_name\":\"가\"}"),
                                OpenDartFixtures.json(
                                        "{\"rcept_no\":\"20260201000001\",\"corp_name\":\"가\"}")));

        assertThat(tool.collect(question("000660", "증자")))
                .extracting(Evidence::id)
                .containsExactly("capital:20260301000001", "capital:20260201000001");
    }

    @Test
    @DisplayName("쓸 값이 하나도 없으면 접수 사실만 적는다")
    void noValues() {
        when(client.list(eq("piicDecsn"), anyMap()))
                .thenReturn(
                        List.of(
                                OpenDartFixtures.json(
                                        "{\"rcept_no\":\"20260710000008\",\"corp_name\":\"SK하이닉스\",\"ic_mthn\":\"-\"}")));

        assertThat(tool.collect(question("000660", "유증")).get(0).text())
                .isEqualTo("SK하이닉스의 유상증자 결정 공시(2026년 7월 10일 접수)가 접수되었습니다.");
    }

    @Test
    @DisplayName("비어 있으면 '조회되지 않았다'는 한 문장이다")
    void none() {
        when(client.list(eq("piicDecsn"), anyMap())).thenReturn(List.of());

        List<Evidence> evidence = tool.collect(question("000660", "증자"));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).id()).isEqualTo("capital:none:000660");
        assertThat(evidence.get(0).text())
                .isEqualTo("OpenDART에서 최근 12개월(2025년 11월 3일~2026년 11월 3일) 유상증자 결정 공시가 조회되지 않았습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"유증 했나요", "유상증자 공시", "증자 소식"})
    @DisplayName("유증·유상증자·증자가 있으면 후보다")
    void relevant(String text) {
        assertThat(tool.isRelevant(question("000660", text))).isTrue();
        assertThat(tool.isRelevant(question("000660", "배당"))).isFalse();
    }
}
