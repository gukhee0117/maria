package com.app.maria.domain.community.kkiru;

import static com.app.maria.domain.community.kkiru.OpenDartFixtures.HYNIX;
import static com.app.maria.domain.community.kkiru.OpenDartFixtures.question;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.global.client.opendart.OpenDartClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DividendToolTest {
    private final OpenDartClient client = OpenDartFixtures.enabledClient();
    private final DividendTool tool = new DividendTool(client, OpenDartFixtures.clock());
    private static final String HEAD = "SK하이닉스의 2025년 사업보고서에는 ";

    private void annual2025() {
        when(client.list(
                        eq("alotMatter"),
                        argThat(
                                m ->
                                        "2025".equals(m.get("bsns_year"))
                                                && "11011".equals(m.get("reprt_code")))))
                .thenReturn(OpenDartFixtures.rows("alotMatter_hynix_2025_annual.json"));
    }

    @Test
    @DisplayName("실제 응답으로 네 항목의 근거를 순서대로 만든다(전기·전전기는 있는 것만)")
    void fourEvidence() {
        annual2025();

        List<Evidence> evidence = tool.collect(question("000660", "배당 어땠어요?"));

        assertThat(evidence)
                .extracting(Evidence::text)
                .containsExactly(
                        HEAD + "보통주 주당 현금배당금이 3,000원으로 기재되어 있습니다(전기 2,204원, 전전기 1,200원).",
                        HEAD + "보통주 현금배당수익률이 0.4%로 기재되어 있습니다(전기 1.0%, 전전기 0.9%).",
                        HEAD + "연결 현금배당성향이 4.9%로 기재되어 있습니다(전기 7.7%).",
                        HEAD + "현금배당금총액이 2조 951억원으로 기재되어 있습니다(전기 1조 5,201억원, 전전기 8,257억원).");
        assertThat(evidence)
                .extracting(Evidence::id)
                .containsExactly(
                        "dividend:20260317000635:주당현금배당금",
                        "dividend:20260317000635:현금배당수익률",
                        "dividend:20260317000635:현금배당성향",
                        "dividend:20260317000635:현금배당금총액");
        assertThat(evidence.get(0).url())
                .isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260317000635");
    }

    @Test
    @DisplayName("직전 연도 보고서가 아직 없으면(013) 그 전 연도를 쓴다")
    void fallsBackToTwoYearsAgo() {
        when(client.list(eq("alotMatter"), argThat(m -> "2025".equals(m.get("bsns_year")))))
                .thenReturn(List.of());
        when(client.list(eq("alotMatter"), argThat(m -> "2024".equals(m.get("bsns_year")))))
                .thenReturn(OpenDartFixtures.rows("alotMatter_hynix_2025_annual.json"));

        List<Evidence> evidence = tool.collect(question("000660", "배당"));

        assertThat(evidence.get(0).text()).startsWith("SK하이닉스의 2024년 사업보고서에는");
    }

    @Test
    @DisplayName("두 연도 모두 자료가 없으면 빈 결과다(호출은 최대 2번)")
    void noReport() {
        when(client.list(eq("alotMatter"), anyMap())).thenReturn(List.of());

        assertThat(tool.collect(question("000660", "배당"))).isEmpty();
        verify(client, org.mockito.Mockito.times(2)).list(eq("alotMatter"), anyMap());
    }

    @Test
    @DisplayName("네 항목이 모두 '-'이면 '기재되어 있지 않다'는 한 문장이다(배당하지 않았다는 단정이 아니다)")
    void allDashes() {
        when(client.list(eq("alotMatter"), anyMap()))
                .thenReturn(
                        List.of(
                                OpenDartFixtures.json(
                                        "{\"rcept_no\":\"20260317000635\",\"se\":\"주당 현금배당금(원)\",\"stock_knd\":\"보통주\","
                                                + "\"thstrm\":\"-\",\"frmtrm\":\"-\",\"lwfr\":\"-\"}")));

        List<Evidence> evidence = tool.collect(question("000660", "배당"));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).id()).isEqualTo("dividend:20260317000635:none");
        assertThat(evidence.get(0).text()).isEqualTo(HEAD + "현금배당 금액이 기재되어 있지 않습니다.");
    }

    @Test
    @DisplayName("우선주 행이나 '-' 항목은 쓰지 않는다(수익률은 우선주가 '-'라 보통주만)")
    void skipsPreferredAndDashes() {
        annual2025();

        assertThat(tool.collect(question("000660", "배당")))
                .noneMatch(e -> e.text().contains("우선주"))
                .noneMatch(e -> e.text().contains("주식배당"));
    }

    @Test
    @DisplayName("우선주 행이 보통주 행보다 앞에 와도 보통주 값을 쓴다")
    void commonStockEvenWhenPreferredComesFirst() {
        when(client.list(eq("alotMatter"), anyMap()))
                .thenReturn(
                        List.of(
                                OpenDartFixtures.json(
                                        "{\"rcept_no\":\"20260317000635\",\"se\":\"주당 현금배당금(원)\",\"stock_knd\":\"우선주\","
                                                + "\"thstrm\":\"500\",\"frmtrm\":\"-\",\"lwfr\":\"-\"}"),
                                OpenDartFixtures.json(
                                        "{\"rcept_no\":\"20260317000635\",\"se\":\"주당 현금배당금(원)\",\"stock_knd\":\"보통주\","
                                                + "\"thstrm\":\"3,000\",\"frmtrm\":\"-\",\"lwfr\":\"-\"}")));

        assertThat(tool.collect(question("000660", "배당")).get(0).text())
                .isEqualTo(HEAD + "보통주 주당 현금배당금이 3,000원으로 기재되어 있습니다.");
    }

    @Test
    @DisplayName("대응표에 없는 종목(해외)이나 키가 없으면 호출 없이 빈 결과다")
    void notApplicable() {
        assertThat(tool.collect(question("AAPL", "배당"))).isEmpty();
        verify(client, never()).list(any(), any());

        when(client.isEnabled()).thenReturn(false);
        assertThat(tool.collect(question("000660", "배당"))).isEmpty();
        assertThat(tool.isRelevant(question("000660", "배당"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"배당 어땠어요?", "배 당금 얼마예요", "@끼루 배당"})
    @DisplayName("배당이라는 단어가 있으면 후보다")
    void relevant(String text) {
        assertThat(tool.isRelevant(question("000660", text))).isTrue();
    }

    @Test
    @DisplayName("다른 단어는 후보가 아니다")
    void notRelevant() {
        assertThat(tool.isRelevant(question("000660", "위험등급은요?"))).isFalse();
        Optional<OpenDartClient.Corp> unused = Optional.of(HYNIX);
        assertThat(unused).isPresent();
    }
}
