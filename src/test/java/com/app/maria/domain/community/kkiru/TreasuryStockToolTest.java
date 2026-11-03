package com.app.maria.domain.community.kkiru;

import static com.app.maria.domain.community.kkiru.OpenDartFixtures.question;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.maria.global.client.opendart.OpenDartClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class TreasuryStockToolTest {
    private final OpenDartClient client = OpenDartFixtures.enabledClient();
    private final TreasuryStockTool tool = new TreasuryStockTool(client, OpenDartFixtures.clock());

    private void stub(List<JsonNode> acquisitions, List<JsonNode> disposals) {
        when(client.list(eq("tsstkAqDecsn"), anyMap())).thenReturn(acquisitions);
        when(client.list(eq("tsstkDpDecsn"), anyMap())).thenReturn(disposals);
    }

    @Test
    @DisplayName("설계 예시와 같은 문장: 결정일·수량·금액·취득 예정 기간(같은 해면 연도 생략)·목적")
    void acquisitionSentence() {
        stub(
                OpenDartFixtures.rows(
                        "tsstkAqDecsn_samsung.json",
                        r -> r.path("rcept_no").asText().equals("20260129000003")),
                List.of());

        List<Evidence> evidence = tool.collect(question("005930", "자사주 산다던데"));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).id()).isEqualTo("treasury:acq:20260129000003");
        assertThat(evidence.get(0).text())
                .isEqualTo(
                        "삼성전자의 2026년 1월 28일 자기주식 취득 결정 내용은 예정 수량 보통주 22,000,000주, "
                                + "예정 금액 3조 5,728억원, 취득 예정 기간 2026년 1월 30일부터 4월 29일까지, "
                                + "목적 임직원 주식보상입니다.");
        assertThat(evidence.get(0).url())
                .isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260129000003");
    }

    @Test
    @DisplayName("결과가 날짜순이 아니라서 접수번호 내림차순으로 정렬하고 취득·처분 각각 2건까지만 쓴다")
    void latestTwoEach() {
        stub(
                OpenDartFixtures.rows("tsstkAqDecsn_samsung.json"),
                OpenDartFixtures.rows("tsstkDpDecsn_samsung.json"));

        List<Evidence> evidence = tool.collect(question("005930", "자기주식"));

        assertThat(evidence)
                .extracting(Evidence::id)
                .containsExactly(
                        "treasury:acq:20260821000616",
                        "treasury:acq:20260318001062",
                        "treasury:disp:20261029000539",
                        "treasury:disp:20260713000395");
        // 실제 응답의 경계값: 14,999,999,992,000원은 15조원으로 읽는다
        assertThat(evidence.get(0).text()).contains("예정 금액 15조원");
    }

    @Test
    @DisplayName("처분 문장: 같은 날이면 기간을 날짜 하나로, 억 미만 금액은 쉼표 원이 아니라 억 단위 반올림")
    void disposalSentence() {
        stub(
                List.of(),
                OpenDartFixtures.rows(
                        "tsstkDpDecsn_samsung.json",
                        r -> r.path("rcept_no").asText().equals("20260713000395")));

        assertThat(tool.collect(question("005930", "자사주")).get(0).text())
                .isEqualTo(
                        "삼성전자의 2026년 7월 13일 자기주식 처분 결정 내용은 예정 수량 보통주 1,132,477주, "
                                + "예정 금액 3,228억원, 처분 예정 기간 2026년 7월 13일, "
                                + "목적 임원 등 성과급의 자기주식 지급입니다.");
    }

    @Test
    @DisplayName("값이 '-'인 항목은 문장에서 뺀다(쓸 수 있는 값이 없으면 접수 사실만)")
    void dashesAreSkipped() {
        stub(
                List.of(
                        OpenDartFixtures.json(
                                "{\"rcept_no\":\"20260129000003\",\"corp_name\":\"삼성전자\",\"aq_dd\":\"2026년 01월 28일\","
                                        + "\"aqpln_stk_ostk\":\"-\",\"aqpln_prc_ostk\":\"-\",\"aqexpd_bgd\":\"-\","
                                        + "\"aqexpd_edd\":\"-\",\"aq_pp\":\"-\"}")),
                List.of());

        assertThat(tool.collect(question("005930", "자사주")).get(0).text())
                .isEqualTo("삼성전자의 2026년 1월 28일 자기주식 취득 결정 공시가 접수되었습니다.");
    }

    @Test
    @DisplayName("둘 다 비면 '조회되지 않았다'는 한 문장이다(최근 12개월 날짜 포함, 없다고 단정하지 않음)")
    void noneFound() {
        stub(List.of(), List.of());

        List<Evidence> evidence = tool.collect(question("005930", "자사주"));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).id()).isEqualTo("treasury:none:005930");
        assertThat(evidence.get(0).text())
                .isEqualTo(
                        "OpenDART에서 최근 12개월(2025년 11월 3일~2026년 11월 3일) 자기주식 취득·처분 결정 공시가 조회되지 않았습니다.");
        assertThat(evidence.get(0).url()).isNull();
    }

    @Test
    @DisplayName("최근 12개월(업무시계 기준) 범위로 두 API를 부른다")
    @SuppressWarnings("unchecked")
    void requestRange() {
        stub(List.of(), List.of());

        tool.collect(question("005930", "자사주"));

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).list(eq("tsstkAqDecsn"), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("corp_code", "00126380")
                .containsEntry("bgn_de", "20251103")
                .containsEntry("end_de", "20261103");
        verify(client).list(eq("tsstkDpDecsn"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"자사주 산다던데", "자기주식 취득 공시", "자시주 소각?"})
    @DisplayName("자사주·자기주식·자시주가 있으면 후보다")
    void relevant(String text) {
        assertThat(tool.isRelevant(question("005930", text))).isTrue();
        assertThat(tool.isRelevant(question("005930", "배당"))).isFalse();
    }
}
