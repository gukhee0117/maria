package com.app.maria.domain.community.service;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 끼루에게 물은 내용이 답할 수 없는 범위(매수·매도 판단, 전망, 추천, 목표가)인지 코드로 먼저 가른다. 범위 밖이면 LLM을 부르지 않는다.
 *
 * <p>범위 밖 표현은 {@code kkiru/out-of-scope-phrases.txt}에서 읽는다(앱이 뜰 때 한 번).
 *
 * <p>키워드 방식이라 우회할 수 있다. 전문가 답변이 붙을 때 LLM 출력의 분류와 금지 표현 검증으로 한 번 더 막는다. 제도 질문에도 흔히 나오는
 * "매수"·"매도"·"수익" 같은 단어 하나만으로는 막지 않는다(예: "매도 결제일이 언제예요?").
 */
@Component
public class KkiruQuestionClassifier {
    private static final String RESOURCE = "/kkiru/out-of-scope-phrases.txt";

    // 공백을 모두 뺀 소문자 표현.
    private final List<String> phrases = loadPhrases();

    public boolean isOutOfScope(String text) {
        String compact = compact(text);
        return phrases.stream().anyMatch(compact::contains);
    }

    private static String compact(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    /** 빈 줄과 # 주석을 뺀다. 파일이 없거나 표현이 하나도 없으면 필터가 조용히 꺼지므로 설정 오류로 본다. */
    private static List<String> loadPhrases() {
        try (InputStream in = KkiruQuestionClassifier.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new AppException(ErrorType.COMMUNITY_TEXT_RULE_LOAD_FAILED, RESOURCE);
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> loaded = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                loaded.add(compact(line));
            }
            if (loaded.isEmpty()) {
                throw new AppException(ErrorType.COMMUNITY_TEXT_RULE_LOAD_FAILED, RESOURCE);
            }
            return List.copyOf(loaded);
        } catch (IOException e) {
            throw new AppException(ErrorType.COMMUNITY_TEXT_RULE_LOAD_FAILED, RESOURCE, e);
        }
    }
}
