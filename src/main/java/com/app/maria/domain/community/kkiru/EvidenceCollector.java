package com.app.maria.domain.community.kkiru;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 질문에 맞는 도구(최대 3개)를 골라 동시에 돌려 근거를 모은다. 도구 하나가 예외를 던지거나 시간이 오래 걸려도 그 도구만 빠지고 나머지 근거는 그대로 쓴다. 자료가 없는
 * 것과 오류는 구분해서 돌려준다.
 */
@Slf4j
@Component
public class EvidenceCollector {
    static final int MAX_TOOLS = 3;
    static final int MAX_EVIDENCE = 12;
    // 질문에서 맞는 단어를 못 찾았을 때 쓰는 기본 묶음
    private static final Set<String> DEFAULT_TOOL_NAMES = Set.of("disclosure", "risk");

    // 도구가 하나도 없어도 앱이 뜨도록 List가 아니라 ObjectProvider로 받는다.
    private final ObjectProvider<EvidenceTool> tools;
    private final Executor executor;

    /** 도구 하나당 기다리는 시간. 테스트가 짧게 줄일 수 있게 setter를 패키지에 연다. */
    @Setter(AccessLevel.PACKAGE)
    @Value("${maria.community.kkiru.tool-timeout-millis:12000}")
    private long toolTimeoutMillis = 12_000L;

    public EvidenceCollector(
            ObjectProvider<EvidenceTool> tools,
            @Qualifier("communityKkiruToolExecutor") Executor executor) {
        this.tools = tools;
        this.executor = executor;
    }

    public Collected collect(KkiruQuestion question) {
        Selection selection = select(question);
        List<EvidenceTool> selected = selection.tools();
        List<CompletableFuture<List<Evidence>>> futures =
                selected.stream()
                        .map(
                                tool ->
                                        CompletableFuture.supplyAsync(
                                                        () -> tool.collect(question), executor)
                                                .orTimeout(
                                                        toolTimeoutMillis, TimeUnit.MILLISECONDS))
                        .toList();
        Map<String, Evidence> unique = new LinkedHashMap<>();
        int failed = 0;
        for (int i = 0; i < selected.size(); i++) {
            try {
                futures.get(i).join().forEach(e -> unique.putIfAbsent(e.id(), e));
            } catch (CompletionException e) {
                failed++;
                log.warn("[KKIRU_TOOL_FAILED] tool={}", selected.get(i).name(), e);
            }
        }
        return new Collected(
                unique.values().stream().limit(MAX_EVIDENCE).toList(),
                selected.stream().map(EvidenceTool::name).toList(),
                failed,
                selection.defaultBundle());
    }

    /**
     * 질문에 맞는 도구를 최대 3개 고른다. 종목이 없는 글에서는 종목이 필요 없는 도구(토론 요약)만 후보다. 후보가 없으면 기본 묶음(공시·위험)인데, 이것도 종목이
     * 있어야 의미가 있다. 기본 묶음으로 간 질문은 로그에 남겨 단어 표를 키운다.
     */
    private Selection select(KkiruQuestion question) {
        boolean hasTicker = question.ticker() != null;
        List<EvidenceTool> usable =
                tools.orderedStream().filter(t -> hasTicker || !t.needsTicker()).toList();
        List<EvidenceTool> picked =
                usable.stream().filter(t -> t.isRelevant(question)).limit(MAX_TOOLS).toList();
        if (!picked.isEmpty() || !hasTicker) {
            return new Selection(picked, false);
        }
        log.info(
                "[KKIRU_DEFAULT_TOOLS] question={}",
                question.text().substring(0, Math.min(40, question.text().length())));
        return new Selection(
                usable.stream().filter(t -> DEFAULT_TOOL_NAMES.contains(t.name())).toList(), true);
    }

    private record Selection(List<EvidenceTool> tools, boolean defaultBundle) {}

    /**
     * 모은 근거(중복 id 제외, 최대 12개), 쓴 도구 이름, 오류 난 도구 수, 질문에서 맞는 단어를 못 찾아 기본 묶음(공시·위험)으로 갔는지. 기본 묶음의 근거는
     * 질문과 맞는다는 보장이 없다.
     */
    public record Collected(
            List<Evidence> evidence,
            List<String> toolNames,
            int failedTools,
            boolean defaultBundle) {
        public Collected(List<Evidence> evidence, List<String> toolNames, int failedTools) {
            this(evidence, toolNames, failedTools, false);
        }
    }
}
