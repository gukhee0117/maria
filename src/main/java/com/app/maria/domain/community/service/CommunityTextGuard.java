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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 커뮤니티 글·댓글·닉네임 금칙어 검사. 규칙은 docs/banned-words-v2/README.md의 "검사 규칙"이고, 그 폴더의 기준
 * 구현(check_reference.py)과 같은 결과를 내야 한다(CommunityTextGuardTest가 test-cases.txt로 확인한다).
 *
 * <p>호출하는 쪽이 먼저 본문 길이(500자)를 검사한다. 이 클래스는 정규식을 쓰므로 방어용으로 {@value #MAX_CHECK_LENGTH}자를 넘는 입력은 거부한다.
 */
@Component
public class CommunityTextGuard {
    public static final int MAX_CHECK_LENGTH = 2_000;

    private static final String RESOURCE_DIR = "/banned-words/";
    private static final int PATTERN_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final String CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
    private static final String[] JONG = {
        "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ",
        "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    };

    /** 첫가끝 자모(U+1100~11FF) → 호환 자모. 유니코드 이름으로 짝을 맞춘다. */
    private static final Map<Integer, Character> CONJOINING_TO_COMPAT = buildConjoiningMap();

    // 닉네임 숫자 마스킹 패턴: 휴대폰 번호, 계좌번호처럼 보이는 숫자 묶음
    private static final Pattern PHONE =
            Pattern.compile(
                    "(?<!\\d)(01[016789])([\\s.\\-]?)(\\d{3,4})([\\s.\\-]?)(\\d{4})(?!\\d)");
    private static final Pattern ACCOUNT =
            Pattern.compile(
                    "(?<![\\d-])\\d{2,6}(?:-\\d{2,8}){1,3}(?![\\d-])|(?<!\\d)\\d{10,16}(?!\\d)");

    private final List<String> profanity;
    private final List<String> spam;
    private final List<String> review;
    private final List<String> impersonation;
    private final List<String> allow;
    private final List<Pattern> spamLinks;
    private final List<Pattern> reviewLinks;
    private final List<Pattern> allowPatterns;

    public CommunityTextGuard() {
        this.profanity = loadWords("profanity.txt");
        this.spam = loadWords("spam.txt");
        this.review = loadWords("review.txt");
        this.impersonation = loadWords("impersonation.txt");
        this.allow = loadWords("allowlist.txt");
        this.spamLinks = loadPatterns("spam-link-patterns.txt");
        this.reviewLinks = loadPatterns("review-link-patterns.txt");
        this.allowPatterns = loadPatterns("allow-patterns.txt");
    }

    /** 순수 판정. 예외를 던지지 않는다(너무 긴 입력은 IllegalArgumentException). */
    public TextVerdict check(String text, TextTarget target) {
        if (text == null) {
            throw new IllegalArgumentException("검사할 글이 없습니다.");
        }
        if (text.length() > MAX_CHECK_LENGTH) {
            throw new IllegalArgumentException("검사할 글이 너무 깁니다.");
        }
        String cleaned = text;
        for (Pattern p : allowPatterns) { // "5개년" 같은 정상 표현을 먼저 지운다
            cleaned = p.matcher(cleaned).replaceAll(" ");
        }
        if (target == TextTarget.NICKNAME) {
            return checkNickname(cleaned);
        }
        String folded = Normalizer.normalize(cleaned, Normalizer.Form.NFKC);
        boolean linkBlocked = spamLinks.stream().anyMatch(p -> p.matcher(folded).find());
        if (linkBlocked || hasHit(cleaned, concat(profanity, spam))) {
            return TextVerdict.BLOCK;
        }
        boolean reviewLink = reviewLinks.stream().anyMatch(p -> p.matcher(folded).find());
        if (reviewLink || hasHit(cleaned, review)) {
            return TextVerdict.REVIEW;
        }
        return TextVerdict.PASS;
    }

    /**
     * 글·댓글 본문 검사. BLOCK이면 COMMUNITY_TEXT_BANNED, 아니면 휴대폰·계좌번호처럼 보이는 숫자를 가린 본문을 돌려준다. REVIEW는 막지
     * 않는다.
     */
    public String requireAllowed(String content) {
        if (check(content, TextTarget.CONTENT) == TextVerdict.BLOCK) {
            throw new AppException(ErrorType.COMMUNITY_TEXT_BANNED);
        }
        return maskPersonalNumbers(content);
    }

    /** 휴대폰 번호는 앞 3자리만, 계좌번호처럼 보이는 숫자 묶음(10~16자리)은 전부 *로 가린다. 구분 기호는 남긴다. */
    static String maskPersonalNumbers(String text) {
        Matcher phone = PHONE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (phone.find()) {
            phone.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            phone.group(1)
                                    + phone.group(2)
                                    + "*".repeat(phone.group(3).length())
                                    + phone.group(4)
                                    + "****"));
        }
        phone.appendTail(out);

        Matcher account = ACCOUNT.matcher(out.toString());
        StringBuilder result = new StringBuilder();
        while (account.find()) {
            String found = account.group();
            long digits = found.chars().filter(Character::isDigit).count();
            String replacement =
                    (digits >= 10 && digits <= 16) ? found.replaceAll("\\d", "*") : found;
            account.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        account.appendTail(result);
        return result.toString();
    }

    private TextVerdict checkNickname(String cleaned) {
        if (hasHit(cleaned, concat(profanity, impersonation))) {
            return TextVerdict.BLOCK;
        }
        // 닉네임은 짧고 띄어쓰기가 없어 경계 규칙이 의미 없다 → 공백을 무시하고 사칭어만 한 번 더 검사한다.
        String compact = normalize(cleaned).compact();
        boolean impersonating = impersonation.stream().anyMatch(compact::contains);
        return impersonating ? TextVerdict.BLOCK : TextVerdict.PASS;
    }

    private boolean hasHit(String text, List<String> words) {
        Normalized n = normalize(text);
        List<int[]> allowedSpans = new ArrayList<>();
        for (String a : allow) {
            addSpans(n.compact(), a, allowedSpans);
        }
        for (String word : words) {
            List<int[]> spans = new ArrayList<>();
            addSpans(n.compact(), word, spans);
            for (int[] span : spans) {
                int s = span[0];
                int e = span[1];
                if (allowedSpans.stream().anyMatch(a -> a[0] <= s && e <= a[1])) {
                    continue; // 정상 단어 안의 매칭
                }
                int t0 = n.owner()[s];
                int t1 = n.owner()[e - 1];
                if (t0 != t1 && !(s == n.starts()[t0] && e == n.ends()[t1])) {
                    continue; // 단어 경계를 넘는 우연한 매칭
                }
                return true;
            }
        }
        return false;
    }

    private static void addSpans(String haystack, String needle, List<int[]> out) {
        if (needle.isEmpty()) {
            return;
        }
        int i = haystack.indexOf(needle);
        while (i != -1) {
            out.add(new int[] {i, i + needle.length()});
            i = haystack.indexOf(needle, i + 1);
        }
    }

    /** 공백 없는 문자열(compact)과, 각 글자가 속한 토큰 번호·토큰의 시작/끝 위치. */
    private record Normalized(String compact, int[] owner, int[] starts, int[] ends) {}

    private static Normalized normalize(String text) {
        StringBuilder folded = new StringBuilder();
        text.codePoints().forEach(cp -> folded.append(charFold(cp)));
        List<String> tokens = new ArrayList<>();
        for (String raw : WHITESPACE.split(folded.toString(), -1)) {
            StringBuilder kept = new StringBuilder();
            raw.chars().filter(CommunityTextGuard::isKept).forEach(c -> kept.append((char) c));
            String token = compose(kept.toString());
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        StringBuilder compact = new StringBuilder();
        List<Integer> owner = new ArrayList<>();
        int[] starts = new int[tokens.size()];
        int[] ends = new int[tokens.size()];
        for (int ti = 0; ti < tokens.size(); ti++) {
            starts[ti] = compact.length();
            compact.append(tokens.get(ti));
            for (int k = 0; k < tokens.get(ti).length(); k++) {
                owner.add(ti);
            }
            ends[ti] = compact.length();
        }
        return new Normalized(
                compact.toString(),
                owner.stream().mapToInt(Integer::intValue).toArray(),
                starts,
                ends);
    }

    /** 한글은 NFKC를 쓰지 않는다(호환 자모가 첫가끝 자모로 바뀌어 지워진다). 첫가끝 자모만 호환 자모로 되돌린다. */
    private static String charFold(int cp) {
        if (isHangul(cp)) {
            Character compat = CONJOINING_TO_COMPAT.get(cp);
            return compat != null ? String.valueOf(compat) : new String(Character.toChars(cp));
        }
        String nfkc = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
        String nfkd = Normalizer.normalize(nfkc, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder();
        nfkd.codePoints()
                .filter(c -> Character.getType(c) != Character.NON_SPACING_MARK)
                .forEach(c -> out.appendCodePoint(c));
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isHangul(int cp) {
        return (cp >= 0x1100 && cp <= 0x11FF)
                || (cp >= 0x3130 && cp <= 0x318F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xA960 && cp <= 0xA97F)
                || (cp >= 0xD7B0 && cp <= 0xD7FF);
    }

    private static boolean isKept(int c) {
        return (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0x3131 && c <= 0x314E)
                || (c >= 0x314F && c <= 0x3163)
                || (c >= 'a' && c <= 'z');
    }

    /** 호환 자모열을 음절로 조합한다(ㅅㅣㅂㅏㄹ → 시발). 자음+모음 쌍이 있을 때만 조합한다. */
    private static String compose(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int cho = CHO.indexOf(c);
            if (cho >= 0 && i + 1 < n && JUNG.indexOf(s.charAt(i + 1)) >= 0) {
                int code = 0xAC00 + (cho * 21 + JUNG.indexOf(s.charAt(i + 1))) * 28;
                i += 2;
                if (i < n) {
                    int jong = jongIndex(s.charAt(i));
                    boolean nextIsVowel = i + 1 < n && JUNG.indexOf(s.charAt(i + 1)) >= 0;
                    if (jong > 0 && !nextIsVowel) {
                        code += jong;
                        i++;
                    }
                }
                out.append((char) code);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int jongIndex(char c) {
        for (int i = 1; i < JONG.length; i++) {
            if (JONG[i].charAt(0) == c) {
                return i;
            }
        }
        return -1;
    }

    private static Map<Integer, Character> buildConjoiningMap() {
        Map<String, Character> compatByName = new HashMap<>();
        for (int cp = 0x3130; cp <= 0x318F; cp++) {
            String name = Character.getName(cp);
            if (name != null && name.startsWith("HANGUL LETTER ")) {
                compatByName.put(name.substring("HANGUL LETTER ".length()), (char) cp);
            }
        }
        Map<Integer, Character> map = new HashMap<>();
        for (int cp = 0x1100; cp <= 0x11FF; cp++) {
            String name = Character.getName(cp);
            if (name == null) {
                continue;
            }
            String base =
                    name.replace("HANGUL CHOSEONG ", "")
                            .replace("HANGUL JUNGSEONG ", "")
                            .replace("HANGUL JONGSEONG ", "");
            Character compat = compatByName.get(base);
            if (compat != null) {
                map.put(cp, compat);
            }
        }
        return map;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static List<String> loadWords(String name) {
        List<String> words = new ArrayList<>();
        for (String line : readLines(name)) {
            String normalized = normalize(line).compact();
            if (!normalized.isEmpty()) {
                words.add(normalized);
            }
        }
        return words;
    }

    private static List<Pattern> loadPatterns(String name) {
        List<Pattern> patterns = new ArrayList<>();
        for (String line : readLines(name)) {
            patterns.add(Pattern.compile(line, PATTERN_FLAGS));
        }
        return patterns;
    }

    /** 빈 줄과 # 주석을 빼고, 탭 앞의 첫 칸만 쓴다. 파일이 없으면 설정 오류이므로 예외를 던진다. */
    private static List<String> readLines(String name) {
        try (InputStream in = CommunityTextGuard.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                throw new AppException(
                        ErrorType.COMMUNITY_TEXT_RULE_LOAD_FAILED, RESOURCE_DIR + name);
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                lines.add(line.split("\t", -1)[0].strip());
            }
            return lines;
        } catch (IOException e) {
            throw new AppException(
                    ErrorType.COMMUNITY_TEXT_RULE_LOAD_FAILED, RESOURCE_DIR + name, e);
        }
    }
}
