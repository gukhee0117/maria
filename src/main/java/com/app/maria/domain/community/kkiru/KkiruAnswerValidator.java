package com.app.maria.domain.community.kkiru;

import com.app.maria.domain.community.service.CommunityTextGuard;
import com.app.maria.domain.community.service.TextTarget;
import com.app.maria.domain.community.service.TextVerdict;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 끼루 답변을 검증한다. 문장마다 (1) 근거가 있고 그 근거가 실제로 모은 것 안에 있으며 (2) 문장 속 숫자가 인용한 근거에 있고 (3) 의견·권유 표현이 없고 (4)
 * 시스템 프롬프트가 새어 나온 흔적이 없고 (5) 금칙어 검사를 통과해야 한다. 주장 확인 답은 판정이 숫자와 모순되지 않아야 한다. 하나라도 어기면 답 전체를 버린다.
 */
@Component
@RequiredArgsConstructor
public class KkiruAnswerValidator {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    // 공백을 모두 뺀 소문자 문장에서 찾는다.
    private static final List<String> OPINION_PHRASES =
            List.of(
                    "사세요", "사라", "사야", "매수추천", "매도추천", "팔아", "파세요", "오를것", "오릅니다", "오를듯", "내릴것",
                    "떨어질것", "목표가", "목표주가", "유망", "강력추천", "좋은종목", "나쁜종목", "확실히오", "반드시오", "수익보장",
                    "담으세요", "담아라");

    // 시스템 프롬프트·입력 형식이 답에 새어 나온 흔적(공백을 모두 뺀 소문자 문장에서 찾는다). 금융 답에는 나올 일이 없는 말이다.
    private static final List<String> LEAK_MARKERS =
            List.of("sourceids", "evidence", "시스템프롬프트", "프롬프트", "당신은종목토론방", "json");

    private final CommunityTextGuard textGuard;

    public Optional<KkiruAnswer> validate(KkiruAnswer answer) {
        if (answer == null
                || answer.sentences() == null
                || answer.sentences().isEmpty()
                || answer.evidence() == null) {
            return Optional.empty();
        }
        for (KkiruSentence sentence : answer.sentences()) {
            if (!isValid(sentence, answer)) {
                return Optional.empty();
            }
        }
        if (!claimVerdictIsConsistent(answer)) {
            return Optional.empty();
        }
        return Optional.of(answer);
    }

    /**
     * 주장 확인 답의 판정이 숫자와 모순되지 않는지 본다(LLM의 비교를 코드가 완전히 검증할 수는 없지만, 눈에 띄는 모순은 막는다). 주장({@code claim:}
     * 근거)의 숫자가 모두 공식 자료 근거에 있는데 "다릅니다"라고 하거나, 하나라도 없는데 "일치합니다"라고 하면 틀린 판정으로 보고 답을 버린다. 판정은 첫 문장에서
     * 읽는다. 주장에 숫자가 없으면 검사하지 않는다.
     */
    private static boolean claimVerdictIsConsistent(KkiruAnswer answer) {
        Evidence claim =
                answer.evidence().values().stream()
                        .filter(e -> e.id().startsWith("claim:"))
                        .findFirst()
                        .orElse(null);
        if (claim == null) {
            return true;
        }
        Set<String> claimNumbers = numbersIn(claim.text());
        if (claimNumbers.isEmpty()) {
            return true;
        }
        Set<String> officialNumbers = new HashSet<>();
        answer.evidence().values().stream()
                .filter(e -> !e.userContent())
                .forEach(e -> officialNumbers.addAll(numbersIn(e.text())));
        boolean allInOfficial = officialNumbers.containsAll(claimNumbers);
        String first = compact(answer.sentences().get(0).text());
        boolean saysMatch = first.contains("일치합니다");
        boolean saysDiffer = first.contains("다릅니다") || first.contains("일치하지않습니다");
        if (saysMatch && !allInOfficial) {
            return false;
        }
        return !(saysDiffer && allInOfficial);
    }

    private boolean isValid(KkiruSentence sentence, KkiruAnswer answer) {
        if (sentence == null
                || sentence.text() == null
                || sentence.text().isBlank()
                || sentence.sourceIds() == null
                || sentence.sourceIds().isEmpty()) {
            return false;
        }
        Set<String> citedNumbers = new HashSet<>();
        for (String sourceId : sentence.sourceIds()) {
            Evidence evidence = answer.evidence().get(sourceId);
            if (evidence == null) {
                return false; // 모으지 않은 근거를 인용했다
            }
            citedNumbers.addAll(numbersIn(evidence.text()));
        }
        if (!citedNumbers.containsAll(numbersIn(sentence.text()))) {
            return false; // 근거에 없는 숫자
        }
        String compact = compact(sentence.text());
        if (OPINION_PHRASES.stream().anyMatch(compact::contains)) {
            return false;
        }
        if (LEAK_MARKERS.stream().anyMatch(compact::contains)) {
            return false;
        }
        return sentence.text().length() <= CommunityTextGuard.MAX_CHECK_LENGTH
                && textGuard.check(sentence.text(), TextTarget.CONTENT) != TextVerdict.BLOCK;
    }

    /** 천 단위 쉼표와 선행 0(01 = 1, 000660 = 660)을 뺀 숫자 모음. 전각 숫자도 같게 본다. */
    private static Set<String> numbersIn(String text) {
        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (matcher.find()) {
            numbers.add(matcher.group().replace(",", "").replaceFirst("^0+(?=\\d)", ""));
        }
        return numbers;
    }

    private static String compact(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
