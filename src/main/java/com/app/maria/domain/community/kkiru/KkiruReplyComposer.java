package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.entity.CommunityContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 검증을 통과한 답을 댓글 본문(제목 없는 문단 하나)과 근거 목록(JSON)으로 만든다. 댓글은 {@value CommunityContent#MAX_LENGTH}자를 넘을 수
 * 없어서, 넘으면 뒤 문장부터 뺀다. 첫 문장조차 들어가지 않으면 답을 만들지 못한 것으로 본다. 고지 문구("끼루는 정보를 안내해요…")는 본문에 넣지 않고 화면이 고정
 * 문구로 보여 준다.
 */
@Component
@RequiredArgsConstructor
public class KkiruReplyComposer {
    private final ObjectMapper objectMapper;

    /** 본문과 근거 JSON. 만들 수 없으면 empty. */
    public Optional<Composed> compose(KkiruAnswer answer) {
        List<KkiruSentence> kept = new ArrayList<>(answer.sentences());
        while (!kept.isEmpty()) {
            String content =
                    kept.stream().map(KkiruSentence::text).collect(Collectors.joining(" "));
            if (content.codePointCount(0, content.length()) <= CommunityContent.MAX_LENGTH) {
                return Optional.of(new Composed(content, sourcesJson(kept, answer.evidence())));
            }
            kept.remove(kept.size() - 1);
        }
        return Optional.empty();
    }

    /** 근거 칩용 JSON: [{"id", "label": 근거 문장, "url": 출처 링크 또는 null}]. 쓰인 근거만, 같은 근거는 한 번만 담는다. */
    private String sourcesJson(List<KkiruSentence> sentences, Map<String, Evidence> evidence) {
        Map<String, Map<String, String>> unique = new LinkedHashMap<>();
        for (KkiruSentence sentence : sentences) {
            for (String id : sentence.sourceIds()) {
                Evidence e = evidence.get(id);
                Map<String, String> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("label", e.text());
                item.put("url", e.url());
                unique.putIfAbsent(id, item);
            }
        }
        try {
            return objectMapper.writeValueAsString(unique.values());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("근거 목록을 JSON으로 바꾸지 못했습니다.", e);
        }
    }

    public record Composed(String content, String sourcesJson) {}
}
