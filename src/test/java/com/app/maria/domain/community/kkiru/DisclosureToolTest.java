package com.app.maria.domain.community.kkiru;

import static com.app.maria.domain.community.kkiru.OpenDartFixtures.question;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.global.client.opendart.OpenDartClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class DisclosureToolTest {
    private final OpenDartClient client = OpenDartFixtures.enabledClient();
    private final DisclosureTool tool = new DisclosureTool(client, OpenDartFixtures.clock());

    @Test
    @DisplayName("실제 응답: 총 건수(13건) 한 문장 + 접수번호 내림차순 최신 5건, 보고서명은 trim, 공시자 이름은 쓰지 않는다")
    void countAndLatestFive() {
        when(client.page(eq("list"), anyMap()))
                .thenReturn(OpenDartFixtures.page("list_hynix_30days.json"));

        List<Evidence> evidence = tool.collect(question("000660", "공시 있어요?"));

        assertThat(evidence).hasSize(6);
        assertThat(evidence.get(0).id()).isEqualTo("disclosure:count:000660");
        assertThat(evidence.get(0).text()).isEqualTo("SK하이닉스의 최근 30일 공시는 13건입니다.");
        assertThat(evidence.get(1).text())
                .isEqualTo("2026년 10월 29일에 \"풍문또는보도에대한해명(미확정)\" 공시가 접수되었습니다.");
        assertThat(evidence.get(1).id()).isEqualTo("disclosure:20261029800130");
        assertThat(evidence.get(1).url()).endsWith("rcpNo=20261029800130");
        List<String> latestIds = evidence.stream().skip(1).map(Evidence::id).toList();
        assertThat(latestIds).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(evidence).noneMatch(e -> e.text().contains("개인 이름") || e.text().contains("flr"));
    }

    @Test
    @DisplayName("최근 30일(업무시계) 범위·최종보고서만·10건 단위로 한 번 조회한다")
    @SuppressWarnings("unchecked")
    void requestParams() {
        when(client.page(eq("list"), anyMap()))
                .thenReturn(OpenDartFixtures.page("list_hynix_30days.json"));

        tool.collect(question("000660", "공시"));

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).page(eq("list"), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("corp_code", "00164779")
                .containsEntry("bgn_de", "20261004")
                .containsEntry("end_de", "20261103")
                .containsEntry("last_reprt_at", "Y")
                .containsEntry("page_count", "10");
    }

    @Test
    @DisplayName("0건이면 '조회되지 않았다'는 한 문장이다")
    void none() {
        when(client.page(eq("list"), anyMap())).thenReturn(new OpenDartClient.Page(List.of(), 0));

        List<Evidence> evidence = tool.collect(question("000660", "공시"));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).id()).isEqualTo("disclosure:none:000660");
        assertThat(evidence.get(0).text()).isEqualTo("최근 30일 동안 공시가 조회되지 않았습니다.");
    }

    @Test
    @DisplayName("보고서명이 비어 있는 행은 건너뛴다")
    void blankTitleSkipped() {
        when(client.page(eq("list"), anyMap()))
                .thenReturn(
                        new OpenDartClient.Page(
                                List.of(
                                        OpenDartFixtures.json(
                                                "{\"rcept_no\":\"20261029000001\",\"report_nm\":\"   \",\"rcept_dt\":\"20261029\"}"),
                                        OpenDartFixtures.json(
                                                "{\"rcept_no\":\"20261028000001\",\"report_nm\":\"분기보고서  \",\"rcept_dt\":\"20261028\"}")),
                                2));

        assertThat(tool.collect(question("000660", "공시")))
                .extracting(Evidence::text)
                .containsExactly(
                        "SK하이닉스의 최근 30일 공시는 2건입니다.", "2026년 10월 28일에 \"분기보고서\" 공시가 접수되었습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"공시 났어요?", "DART 확인해줘", "다트 올라왔나", "보고서 나왔어요"})
    @DisplayName("공시·dart·다트·보고서가 있으면 후보다")
    void relevant(String text) {
        assertThat(tool.isRelevant(question("000660", text))).isTrue();
        assertThat(tool.isRelevant(question("000660", "배당"))).isFalse();
    }
}
