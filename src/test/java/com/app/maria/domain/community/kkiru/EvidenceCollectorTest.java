package com.app.maria.domain.community.kkiru;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class EvidenceCollectorTest {
    private static final Executor DIRECT = Runnable::run;

    private static KkiruQuestion question(String ticker, String text) {
        return new KkiruQuestion(1L, null, ticker, text);
    }

    private static EvidenceTool tool(
            String name, boolean relevant, List<Evidence> result, RuntimeException failure) {
        return new EvidenceTool() {
            public String name() {
                return name;
            }

            public boolean isRelevant(KkiruQuestion q) {
                return relevant;
            }

            public List<Evidence> collect(KkiruQuestion q) {
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
    }

    private static EvidenceCollector collectorOf(EvidenceTool... tools) {
        @SuppressWarnings("unchecked")
        ObjectProvider<EvidenceTool> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> Stream.of(tools));
        return new EvidenceCollector(provider, DIRECT);
    }

    private static Evidence ev(String id) {
        return new Evidence(id, id + " 근거.", null);
    }

    @Test
    @DisplayName("관련 도구만 돌려 근거를 순서대로 모은다")
    void collectsRelevantTools() {
        EvidenceCollector collector =
                collectorOf(
                        tool("dividend", true, List.of(ev("d:1")), null),
                        tool("risk", false, List.of(ev("r:1")), null));

        EvidenceCollector.Collected c = collector.collect(question("000660", "배당 어때요"));

        assertThat(c.evidence()).extracting(Evidence::id).containsExactly("d:1");
        assertThat(c.toolNames()).containsExactly("dividend");
        assertThat(c.failedTools()).isZero();
        assertThat(c.defaultBundle()).isFalse(); // 단어로 고른 도구는 기본 묶음이 아니다
    }

    @Test
    @DisplayName("관련 도구가 4개 이상이면 앞의 3개만 쓴다")
    void atMostThreeTools() {
        EvidenceCollector collector =
                collectorOf(
                        tool("a", true, List.of(ev("a")), null),
                        tool("b", true, List.of(ev("b")), null),
                        tool("c", true, List.of(ev("c")), null),
                        tool("d", true, List.of(ev("d")), null));

        assertThat(collector.collect(question("000660", "질문")).toolNames())
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("관련 도구가 없으면 기본 묶음(공시·위험)을 쓴다")
    void defaultBundle() {
        EvidenceCollector collector =
                collectorOf(
                        tool("dividend", false, List.of(ev("d")), null),
                        tool("disclosure", false, List.of(ev("p")), null),
                        tool("risk", false, List.of(ev("r")), null));

        EvidenceCollector.Collected collected = collector.collect(question("000660", "요즘 어때요"));

        assertThat(collected.toolNames()).containsExactly("disclosure", "risk");
        assertThat(collected.defaultBundle()).isTrue();
    }

    @Test
    @DisplayName("종목이 없으면 도구를 돌리지 않는다")
    void noTicker() {
        EvidenceCollector collector = collectorOf(tool("risk", true, List.of(ev("r")), null));

        EvidenceCollector.Collected c = collector.collect(question(null, "위험등급은요"));

        assertThat(c.evidence()).isEmpty();
        assertThat(c.toolNames()).isEmpty();
    }

    private static EvidenceTool tickerlessTool(
            String name, boolean relevant, List<Evidence> result) {
        return new EvidenceTool() {
            public String name() {
                return name;
            }

            public boolean needsTicker() {
                return false;
            }

            public boolean isRelevant(KkiruQuestion q) {
                return relevant;
            }

            public List<Evidence> collect(KkiruQuestion q) {
                return result;
            }
        };
    }

    @Test
    @DisplayName("종목이 없는 글에서도 종목이 필요 없는 도구(토론 요약)는 돈다. 종목이 필요한 도구와 기본 묶음은 안 돈다")
    void tickerlessToolRunsWithoutTicker() {
        EvidenceCollector collector =
                collectorOf(
                        tool("risk", true, List.of(ev("r")), null),
                        tool("disclosure", true, List.of(ev("p")), null),
                        tickerlessTool("discussion", true, List.of(ev("d"))));

        EvidenceCollector.Collected c = collector.collect(question(null, "이 글 요약해줘"));

        assertThat(c.toolNames()).containsExactly("discussion");
        assertThat(c.evidence()).extracting(Evidence::id).containsExactly("d");
    }

    @Test
    @DisplayName("종목이 없고 관련 도구도 없으면 기본 묶음 없이 빈 결과다")
    void noTickerNoDefaultBundle() {
        EvidenceCollector collector =
                collectorOf(
                        tool("disclosure", false, List.of(ev("p")), null),
                        tool("risk", false, List.of(ev("r")), null),
                        tickerlessTool("discussion", false, List.of(ev("d"))));

        assertThat(collector.collect(question(null, "RIA 인출 어떻게 해요")).toolNames()).isEmpty();
    }

    @Test
    @DisplayName("도구 하나가 오류여도 나머지 근거는 쓰고, 오류 수는 센다. 빈 결과는 오류가 아니다")
    void errorIsSeparatedFromEmpty() {
        EvidenceCollector collector =
                collectorOf(
                        tool("dividend", true, List.of(), null),
                        tool("risk", true, null, new IllegalStateException("외부 오류")),
                        tool("disclosure", true, List.of(ev("p:1")), null));

        EvidenceCollector.Collected c = collector.collect(question("000660", "질문"));

        assertThat(c.evidence()).extracting(Evidence::id).containsExactly("p:1");
        assertThat(c.failedTools()).isEqualTo(1);
    }

    @Test
    @DisplayName("모든 도구가 오류면 근거가 없고 오류 수가 도구 수와 같다")
    void allFail() {
        EvidenceCollector collector =
                collectorOf(tool("risk", true, null, new IllegalStateException("x")));

        EvidenceCollector.Collected c = collector.collect(question("000660", "질문"));

        assertThat(c.evidence()).isEmpty();
        assertThat(c.failedTools()).isEqualTo(c.toolNames().size());
    }

    @Test
    @DisplayName("같은 id는 한 번만, 근거는 최대 12개까지 쓴다")
    void dedupeAndCap() {
        List<Evidence> many =
                java.util.stream.IntStream.range(0, 20).mapToObj(i -> ev("m:" + i)).toList();
        EvidenceCollector collector =
                collectorOf(tool("a", true, many, null), tool("b", true, List.of(ev("m:0")), null));

        List<Evidence> evidence = collector.collect(question("000660", "질문")).evidence();

        assertThat(evidence).hasSize(EvidenceCollector.MAX_EVIDENCE);
        assertThat(evidence).extracting(Evidence::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("제한 시간을 넘긴 도구는 오류로 센다")
    void timeout() {
        EvidenceTool slow =
                new EvidenceTool() {
                    public String name() {
                        return "slow";
                    }

                    public boolean isRelevant(KkiruQuestion q) {
                        return true;
                    }

                    public List<Evidence> collect(KkiruQuestion q) {
                        try {
                            Thread.sleep(2_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return List.of(ev("s"));
                    }
                };
        @SuppressWarnings("unchecked")
        ObjectProvider<EvidenceTool> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(i -> Stream.of(slow));
        EvidenceCollector collector =
                new EvidenceCollector(
                        provider, java.util.concurrent.Executors.newCachedThreadPool());
        collector.setToolTimeoutMillis(100);

        EvidenceCollector.Collected c = collector.collect(question("000660", "질문"));

        assertThat(c.evidence()).isEmpty();
        assertThat(c.failedTools()).isEqualTo(1);
    }
}
