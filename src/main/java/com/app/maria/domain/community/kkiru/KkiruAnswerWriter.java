package com.app.maria.domain.community.kkiru;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 모은 근거 안에서 질문에 맞는 답 문장을 쓴다(DB 저장은 {@link KkiruReplyWriter}). LLM이 실패하거나(시간 초과·형식 오류·빈 답·검증 탈락) 답할
 * 수 없다고 하면, 근거 문장을 그대로 이어 붙인 템플릿 답으로 대신한다. 둘 다 못 만들면 empty다.
 */
@Slf4j
@Component
public class KkiruAnswerWriter {
    private static final int MAX_SENTENCES = 3;
    private static final int MAX_QUESTION_LENGTH = 200;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final KkiruAnswerValidator validator;
    private final Executor executor;
    // 프롬프트에 JSON 중괄호가 있어 ChatClient의 기본 시스템 프롬프트(템플릿)로 넣지 않고 메시지로 직접 보낸다.
    private final String systemPrompt;
    // 이용자 글 근거가 있을 때, 주장 확인 근거가 있을 때만 시스템 프롬프트 뒤에 덧붙이는 규칙
    private final String userRules;
    private final String claimRules;

    /** LLM 호출을 기다리는 시간. 테스트가 짧게 줄일 수 있게 setter를 패키지에 연다. */
    @Setter(AccessLevel.PACKAGE)
    @Value("${maria.community.kkiru.llm-timeout-millis:15000}")
    private long llmTimeoutMillis = 15_000L;

    public KkiruAnswerWriter(
            @Qualifier("communityKkiruChatClient") ChatClient chatClient,
            ObjectMapper objectMapper,
            KkiruAnswerValidator validator,
            @Qualifier("communityKkiruToolExecutor") Executor executor)
            throws IOException {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.executor = executor;
        this.systemPrompt = resource("kkiru/community-answer-prompt.txt");
        this.userRules = resource("kkiru/community-answer-user-rules.txt");
        this.claimRules = resource("kkiru/community-answer-claim-rules.txt");
    }

    private static String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    /** 근거 종류에 맞춰 시스템 프롬프트를 만든다: 이용자 글 근거가 있으면 출처 서술 규칙, 주장 근거가 있으면 비교 규칙을 덧붙인다. */
    private String systemPromptFor(List<Evidence> evidence) {
        StringBuilder prompt = new StringBuilder(systemPrompt);
        if (evidence.stream().anyMatch(Evidence::userContent)) {
            prompt.append(userRules);
        }
        if (evidence.stream().anyMatch(e -> e.id().startsWith("claim:"))) {
            prompt.append(claimRules);
        }
        return prompt.toString();
    }

    /** LLM에 넣는 근거 한 개: 이용자 글은 kind를 "user"로 표시해 "그 사람이 그렇게 썼다"로만 다루게 한다. */
    private static Map<String, String> forLlm(Evidence e) {
        return Map.of(
                "id", e.id(),
                "kind", e.userContent() ? "user" : "official",
                "text", clean(e.text(), Integer.MAX_VALUE));
    }

    /** 쓴 답과 템플릿으로 대신했는지(fallback). */
    /** 쓴 결과의 종류: LLM이 쓴 답, 템플릿 답, LLM이 "답할 수 없다"고 해서 답을 내지 않음(answer는 null). */
    public enum Kind {
        LLM,
        TEMPLATE,
        DECLINED
    }

    public record Written(KkiruAnswer answer, Kind kind) {
        public Written(KkiruAnswer answer, boolean fallback) {
            this(answer, fallback ? Kind.TEMPLATE : Kind.LLM);
        }

        public boolean fallback() {
            return kind == Kind.TEMPLATE;
        }

        public boolean declined() {
            return kind == Kind.DECLINED;
        }
    }

    public Optional<Written> write(String question, List<Evidence> evidence) {
        return write(question, evidence, true);
    }

    /**
     * @param evidenceMatchesQuestion 근거가 질문에 맞는 도구(단어 매칭)에서 왔는지. false(기본 묶음)인데 LLM이 "답할 수 없다"고 하면
     *     엉뚱한 근거를 템플릿으로 늘어놓지 않고 답을 내지 않는다 (DECLINED). LLM이 오류·시간 초과·형식 오류·검증 탈락으로 실패한 경우는 이 값과 상관없이
     *     템플릿이다.
     */
    public Optional<Written> write(
            String question, List<Evidence> evidence, boolean evidenceMatchesQuestion) {
        Map<String, Evidence> byId = new LinkedHashMap<>();
        evidence.forEach(e -> byId.put(e.id(), e));
        Optional<KkiruAnswer> asked = askLlm(question, evidence, byId);
        if (asked.isPresent() && asked.get().sentences().isEmpty()) {
            if (!evidenceMatchesQuestion) {
                return Optional.of(new Written(null, Kind.DECLINED));
            }
        } else if (asked.isPresent()) {
            return Optional.of(new Written(asked.get(), false));
        }
        return template(evidence, byId).map(answer -> new Written(answer, true));
    }

    private Optional<KkiruAnswer> askLlm(
            String question, List<Evidence> evidence, Map<String, Evidence> byId) {
        try {
            // 질문과 근거는 데이터로만 넣는다. 제어 문자·줄바꿈은 지운다.
            String input =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "question",
                                    clean(question, MAX_QUESTION_LENGTH),
                                    "evidence",
                                    evidence.stream().map(KkiruAnswerWriter::forLlm).toList()));
            // ponytail: orTimeout은 기다림만 끊고 진행 중인 HTTP 호출은 끊지 못한다. 문제가 되면 모델 옵션 쪽 타임아웃으로.
            String raw =
                    CompletableFuture.supplyAsync(
                                    () ->
                                            chatClient
                                                    .prompt()
                                                    .messages(
                                                            new SystemMessage(
                                                                    systemPromptFor(evidence)),
                                                            new UserMessage(input))
                                                    .call()
                                                    .content(),
                                    executor)
                            .orTimeout(llmTimeoutMillis, TimeUnit.MILLISECONDS)
                            .join();
            Optional<KkiruAnswer> parsed = parse(raw, byId);
            if (parsed.isPresent() && parsed.get().sentences().isEmpty()) {
                return parsed; // LLM이 "답할 수 없다"고 한 것(빈 문장 목록)
            }
            Optional<KkiruAnswer> valid = parsed.flatMap(validator::validate);
            if (parsed.isPresent() && valid.isEmpty()) {
                // 근거는 공개된 공시 사실뿐이라 문장을 그대로 남겨 거절 이유를 볼 수 있게 한다.
                log.info(
                        "[KKIRU_LLM_REJECTED] {}",
                        parsed.get().sentences().stream().map(KkiruSentence::text).toList());
            }
            return valid;
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("[KKIRU_LLM_FAILED] {}", e.toString());
            return Optional.empty();
        }
    }

    /** 코드 블록 표시를 건너뛰고 첫 JSON 객체만 읽는다. 형식이 틀리면 empty, "답할 수 없다"는 빈 답이면 문장이 없는 답(이유는 로그에 남긴다). */
    private Optional<KkiruAnswer> parse(String raw, Map<String, Evidence> byId)
            throws JsonProcessingException {
        int start = raw == null ? -1 : raw.indexOf('{');
        if (start < 0) {
            log.info("[KKIRU_LLM_UNUSABLE] JSON이 아님");
            return Optional.empty();
        }
        JsonNode sentences = objectMapper.readTree(raw.substring(start)).path("sentences");
        if (!sentences.isArray()) {
            log.info("[KKIRU_LLM_UNUSABLE] sentences가 배열이 아님");
            return Optional.empty();
        }
        if (sentences.isEmpty()) {
            log.info("[KKIRU_LLM_DECLINED] LLM이 답할 수 없다고 함");
            return Optional.of(new KkiruAnswer(List.of(), byId)); // 형식 오류(empty)와 구분하는 표시
        }
        if (sentences.size() > MAX_SENTENCES) {
            log.info("[KKIRU_LLM_UNUSABLE] 문장 {}개(최대 {}개)", sentences.size(), MAX_SENTENCES);
            return Optional.empty();
        }
        List<KkiruSentence> parsed = new ArrayList<>();
        for (JsonNode node : sentences) {
            String text = node.path("text").asText("");
            JsonNode ids = node.path("sourceIds");
            if (text.isBlank() || !ids.isArray() || ids.isEmpty()) {
                log.info("[KKIRU_LLM_UNUSABLE] 문장이 비었거나 근거 id가 없음");
                return Optional.empty();
            }
            List<String> sourceIds = new ArrayList<>();
            ids.forEach(id -> sourceIds.add(id.asText()));
            parsed.add(new KkiruSentence(text, sourceIds));
        }
        return Optional.of(new KkiruAnswer(parsed, byId));
    }

    /** 근거 문장을 그대로 문장으로 쓴다. 이용자 글 근거와 검증을 통과하지 못한 근거는 건너뛴다. */
    private Optional<KkiruAnswer> template(List<Evidence> evidence, Map<String, Evidence> byId) {
        List<KkiruSentence> sentences = new ArrayList<>();
        for (Evidence e : evidence) {
            if (e.userContent()) {
                continue; // 이용자 글을 끼루 이름으로 그대로 올리지 않는다
            }
            KkiruSentence sentence = new KkiruSentence(e.text(), List.of(e.id()));
            if (validator.validate(new KkiruAnswer(List.of(sentence), byId)).isPresent()) {
                sentences.add(sentence);
            }
        }
        return sentences.isEmpty()
                ? Optional.empty()
                : Optional.of(new KkiruAnswer(sentences, byId));
    }

    private static String clean(String text, int maxLength) {
        String cleaned = text.replaceAll("\\p{Cntrl}+", " ").trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
