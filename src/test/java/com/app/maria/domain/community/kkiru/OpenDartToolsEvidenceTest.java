package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.app.maria.domain.community.service.CommunityTextGuard;
import com.app.maria.global.client.opendart.OpenDartClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 응답으로 모든 OpenDART 도구의 근거를 만들어, 근거 문장 하나하나가 템플릿 답으로 쓰여도 검증기를 통과하는지(의견 표현·금칙어·길이), id가 겹치지 않는지
 * 확인한다. 템플릿 답은 근거 문장을 그대로 쓰므로 여기서 탈락하는 근거는 답이 되지 못한다.
 */
class OpenDartToolsEvidenceTest {
    private final OpenDartClient client = OpenDartFixtures.enabledClient();
    private final KkiruAnswerValidator validator =
            new KkiruAnswerValidator(new CommunityTextGuard());

    private List<Evidence> everything() {
        when(client.list(eq("alotMatter"), anyMap()))
                .thenReturn(OpenDartFixtures.rows("alotMatter_hynix_2025_annual.json"));
        when(client.list(eq("tsstkAqDecsn"), anyMap()))
                .thenReturn(OpenDartFixtures.rows("tsstkAqDecsn_samsung.json"));
        when(client.list(eq("tsstkDpDecsn"), anyMap()))
                .thenReturn(OpenDartFixtures.rows("tsstkDpDecsn_samsung.json"));
        when(client.list(eq("piicDecsn"), anyMap()))
                .thenReturn(OpenDartFixtures.rows("piicDecsn_hynix.json"));
        when(client.list(eq("fnlttSinglAcnt"), anyMap()))
                .thenReturn(OpenDartFixtures.rows("fnlttSinglAcnt_hynix_2025_annual.json"));
        when(client.list(eq("fnlttSinglIndx"), anyMap()))
                .thenReturn(OpenDartFixtures.rows("fnlttSinglIndx_hynix_2025.json"));
        when(client.page(eq("list"), anyMap()))
                .thenReturn(OpenDartFixtures.page("list_hynix_30days.json"));

        var clock = OpenDartFixtures.clock();
        KkiruQuestion all = OpenDartFixtures.question("000660", "배당 자사주 유증 매출 부채 roe 공시");
        List<Evidence> evidence = new ArrayList<>();
        for (EvidenceTool tool :
                List.of(
                        new DividendTool(client, clock),
                        new TreasuryStockTool(client, clock),
                        new CapitalIncreaseTool(client, clock),
                        new FinancialTool(client, clock),
                        new DisclosureTool(client, clock))) {
            evidence.addAll(tool.collect(all));
        }
        return evidence;
    }

    @Test
    @DisplayName("모든 근거 문장이 템플릿 답으로 검증을 통과하고, id가 겹치지 않으며, 200자 안쪽이다")
    void everyEvidenceIsUsable() {
        List<Evidence> evidence = everything();

        assertThat(evidence).hasSizeGreaterThan(15);
        assertThat(evidence).extracting(Evidence::id).doesNotHaveDuplicates();
        for (Evidence e : evidence) {
            KkiruAnswer answer =
                    new KkiruAnswer(
                            List.of(new KkiruSentence(e.text(), List.of(e.id()))),
                            Map.of(e.id(), e));
            assertThat(validator.validate(answer)).as(e.text()).isPresent();
            assertThat(e.text().length()).as(e.text()).isLessThanOrEqualTo(200);
            assertThat(e.text()).endsWith(".");
        }
    }
}
