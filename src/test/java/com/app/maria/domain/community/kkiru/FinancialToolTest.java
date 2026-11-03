package com.app.maria.domain.community.kkiru;

import static com.app.maria.domain.community.kkiru.OpenDartFixtures.question;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.global.client.opendart.OpenDartClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FinancialToolTest {
    private final OpenDartClient client = OpenDartFixtures.enabledClient();
    private final FinancialTool tool = new FinancialTool(client, OpenDartFixtures.clock());

    private void report(String code, int year, List<JsonNode> rows) {
        when(client.list(
                        eq("fnlttSinglAcnt"),
                        argThat(
                                m ->
                                        code.equals(m.get("reprt_code"))
                                                && String.valueOf(year)
                                                        .equals(m.get("bsns_year")))))
                .thenReturn(rows);
    }

    private void emptyOthers() {
        when(client.list(eq("fnlttSinglAcnt"), anyMap())).thenReturn(List.of());
    }

    @Test
    @DisplayName("올해 3분기가 아직 없으면(013) 반기를 쓴다: 설계 예시 문장(분기 값, 전년 같은 기간 대비, 상반기 누적)")
    void halfYearReport() {
        emptyOthers();
        report("11012", 2026, OpenDartFixtures.rows("fnlttSinglAcnt_hynix_2026_half.json"));

        List<Evidence> evidence = tool.collect(question("000660", "매출 어때요?"));

        assertThat(evidence)
                .extracting(Evidence::text)
                .containsExactly(
                        "SK하이닉스의 2026년 반기(2분기) 연결 매출액은 79조 3,187억원으로 전년 같은 기간 22조 2,320억원 대비 +256.8%입니다. 상반기 누적은 131조 8,950억원입니다.",
                        "SK하이닉스의 2026년 반기(2분기) 연결 영업이익은 60조 5,426억원으로 전년 같은 기간 9조 2,129억원 대비 +557.2%입니다. 상반기 누적은 98조 1,529억원입니다.",
                        "SK하이닉스의 2026년 반기(2분기) 연결 당기순이익(손실)은 93조 9,226억원으로 전년 같은 기간 6조 9,962억원 대비 +1242.5%입니다. 상반기 누적은 134조 2,685억원입니다.");
        assertThat(evidence.get(0).id()).isEqualTo("financial:20260814003509:매출액");
        assertThat(evidence.get(0).url()).contains("rcpNo=20260814003509");
    }

    @Test
    @DisplayName("분기 보고서가 모두 없으면 지난해 사업보고서를 쓴다: 전기 대비 문장")
    void annualFallback() {
        emptyOthers();
        report("11011", 2025, OpenDartFixtures.rows("fnlttSinglAcnt_hynix_2025_annual.json"));

        List<Evidence> evidence = tool.collect(question("000660", "실적"));

        assertThat(evidence.get(0).text())
                .isEqualTo("SK하이닉스의 2025년 연결 매출액은 97조 1,467억원으로 전기 66조 1,930억원 대비 +46.8%입니다.");
        assertThat(evidence.get(1).text())
                .isEqualTo("SK하이닉스의 2025년 연결 영업이익은 47조 2,063억원으로 전기 23조 4,673억원 대비 +101.2%입니다.");
        assertThat(evidence).noneMatch(e -> e.text().contains("누적"));
        verify(client, times(4)).list(eq("fnlttSinglAcnt"), anyMap()); // 3분기→반기→1분기→지난해, 최대 4번
    }

    @Test
    @DisplayName("전부 비어 있으면 빈 결과(호출은 4번)")
    void nothing() {
        emptyOthers();

        assertThat(tool.collect(question("000660", "실적"))).isEmpty();
        verify(client, times(4)).list(eq("fnlttSinglAcnt"), anyMap());
    }

    @Test
    @DisplayName("금융업(KB금융)에 매출을 물으면 '매출액 계정이 없다'를 먼저 알리고 영업이익·순이익·순이자손익을 읽는다")
    void financialCompanyAskedRevenue() {
        emptyOthers();
        report("11011", 2025, OpenDartFixtures.rows("fnlttSinglAcnt_kb_2025_annual.json"));

        List<Evidence> evidence = tool.collect(question("105560", "매출이 얼마예요?"));

        assertThat(evidence)
                .extracting(Evidence::text)
                .containsExactly(
                        "이 회사는 재무제표에 매출액 계정이 없습니다.",
                        "KB금융의 2025년 연결 영업이익(손실)은 8조 5,177억원으로 전기 8조 453억원 대비 +5.9%입니다.",
                        "KB금융의 2025년 연결 당기순이익(손실)은 5조 8,407억원으로 전기 5조 286억원 대비 +16.1%입니다.",
                        "KB금융의 2025년 연결 순이자손익은 13조 731억원으로 전기 12조 8,267억원 대비 +1.9%입니다.");
        assertThat(evidence.get(0).id()).isEqualTo("financial:20260619000667:no-revenue");
    }

    @Test
    @DisplayName("금융업이라도 매출을 묻지 않았으면 안내 문장은 없다")
    void financialCompanyWithoutRevenueQuestion() {
        emptyOthers();
        report("11011", 2025, OpenDartFixtures.rows("fnlttSinglAcnt_kb_2025_annual.json"));

        assertThat(tool.collect(question("105560", "영업이익은요?")))
                .noneMatch(e -> e.text().contains("매출액 계정이 없습니다"))
                .hasSize(3);
    }

    @Test
    @DisplayName("연결이 없으면 별도로 읽고 문장에 '별도'라고 쓴다")
    void separateStatements() {
        emptyOthers();
        report(
                "11012",
                2026,
                List.of(
                        OpenDartFixtures.json(
                                "{\"rcept_no\":\"20260814000001\",\"fs_div\":\"OFS\",\"sj_div\":\"IS\",\"account_nm\":\"매출액\","
                                        + "\"thstrm_amount\":\"1,000,000,000\",\"frmtrm_amount\":\"500,000,000\"}")));

        assertThat(tool.collect(question("000660", "매출")).get(0).text())
                .startsWith("SK하이닉스의 2026년 반기(2분기) 별도 매출액은 10억원으로 전년 같은 기간 5억원 대비 +100.0%입니다.");
    }

    @Test
    @DisplayName("비교값이 0이면 증감률은 비교 불가, 음수에서 양수로 바뀌면 적자에서 흑자로 전환, 비교값이 없으면 이번 값만")
    void changeVariants() {
        emptyOthers();
        report(
                "11011",
                2025,
                List.of(
                        row("매출액", "1,000,000,000,000", "0"),
                        row("영업이익", "3,000,000,000,000", "-2,000,000,000,000"),
                        row("당기순이익(손실)", "5,000,000,000,000", "-")));

        assertThat(tool.collect(question("000660", "실적")))
                .extracting(Evidence::text)
                .containsExactly(
                        "SK하이닉스의 2025년 연결 매출액은 1조원입니다(전기 0원, 증감률은 비교 불가).",
                        "SK하이닉스의 2025년 연결 영업이익은 3조원입니다(전기 -2조원, 적자에서 흑자로 전환).",
                        "SK하이닉스의 2025년 연결 당기순이익(손실)은 5조원입니다.");
    }

    private static JsonNode row(String account, String current, String before) {
        return OpenDartFixtures.json(
                "{\"rcept_no\":\"20260317000001\",\"fs_div\":\"CFS\",\"sj_div\":\"IS\",\"account_nm\":\""
                        + account
                        + "\",\"thstrm_amount\":\""
                        + current
                        + "\",\"frmtrm_amount\":\""
                        + before
                        + "\"}");
    }

    // ---------- 재무지표 ----------

    @Test
    @DisplayName("부채·자본 같은 단어가 있으면 안정성 지표를 같은 보고서에서 가져온다(없는 값은 건너뜀)")
    void stabilityIndicators() {
        emptyOthers();
        report("11011", 2025, OpenDartFixtures.rows("fnlttSinglAcnt_hynix_2025_annual.json"));
        when(client.list(
                        eq("fnlttSinglIndx"),
                        argThat(
                                m ->
                                        "M220000".equals(m.get("idx_cl_code"))
                                                && "11011".equals(m.get("reprt_code")))))
                .thenReturn(OpenDartFixtures.rows("fnlttSinglIndx_hynix_2025.json"));

        List<Evidence> evidence = tool.collect(question("000660", "부채비율 높아요?"));

        assertThat(evidence)
                .extracting(Evidence::text)
                .contains(
                        "SK하이닉스의 2025년 12월 31일 기준 부채비율 수치는 45.9%입니다.",
                        "SK하이닉스의 2025년 12월 31일 기준 자기자본비율 수치는 68.5%입니다.");
        assertThat(evidence).extracting(Evidence::id).contains("financial:idx:M221100:2025:11011");
        verify(client, never())
                .list(eq("fnlttSinglIndx"), argThat(m -> "M210000".equals(m.get("idx_cl_code"))));
    }

    @Test
    @DisplayName("ROE·마진 같은 단어가 있으면 수익성 지표(ROE·순이익률)를 가져온다")
    void profitabilityIndicators() {
        emptyOthers();
        report("11012", 2026, OpenDartFixtures.rows("fnlttSinglAcnt_hynix_2026_half.json"));
        when(client.list(
                        eq("fnlttSinglIndx"), argThat(m -> "M210000".equals(m.get("idx_cl_code")))))
                .thenReturn(OpenDartFixtures.rows("fnlttSinglIndx_hynix_2026_half.json"));

        assertThat(tool.collect(question("000660", "ROE 얼마야")))
                .extracting(Evidence::text)
                .contains(
                        "SK하이닉스의 2026년 6월 30일 기준 ROE 수치는 70.0%입니다.",
                        "SK하이닉스의 2026년 6월 30일 기준 순이익률 수치는 101.8%입니다.");
    }

    @Test
    @DisplayName("지표 단어가 없으면 지표를 부르지 않는다")
    void noIndicatorWithoutKeyword() {
        emptyOthers();
        report("11011", 2025, OpenDartFixtures.rows("fnlttSinglAcnt_hynix_2025_annual.json"));

        tool.collect(question("000660", "매출 알려줘"));

        verify(client, never()).list(eq("fnlttSinglIndx"), any());
    }

    @Test
    @DisplayName("지표 값이 키째 없는 행(idx_val 없음)은 건너뛴다")
    void indicatorWithoutValue() {
        emptyOthers();
        report("11011", 2025, OpenDartFixtures.rows("fnlttSinglAcnt_hynix_2025_annual.json"));
        when(client.list(eq("fnlttSinglIndx"), anyMap()))
                .thenReturn(
                        List.of(
                                OpenDartFixtures.json(
                                        "{\"idx_cl_code\":\"M220000\",\"idx_code\":\"M221100\",\"idx_nm\":\"부채비율\",\"stlm_dt\":\"2025-12-31\"}")));

        assertThat(tool.collect(question("000660", "부채 많아?")))
                .noneMatch(e -> e.id().startsWith("financial:idx:"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "매출 어때요",
                "영업이익은?",
                "적자 났나요",
                "흑자예요?",
                "실적 발표",
                "재무 상태",
                "ROE가 뭐예요",
                "어닝 서프라이즈"
            })
    @DisplayName("재무 관련 단어가 있으면 후보다")
    void relevant(String text) {
        assertThat(tool.isRelevant(question("000660", text))).isTrue();
        assertThat(tool.isRelevant(question("000660", "공시 있나요"))).isFalse();
    }
}
