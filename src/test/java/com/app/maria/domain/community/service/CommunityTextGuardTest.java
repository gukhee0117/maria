package com.app.maria.domain.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommunityTextGuardTest {
    private final CommunityTextGuard guard = new CommunityTextGuard();

    private record Case(TextVerdict expected, TextTarget target, String sentence) {
        @Override
        public String toString() {
            return expected + " " + target + " " + sentence;
        }
    }

    /** docs/banned-words-v2/test-cases.txt와 같은 파일. 형식: 기대값 TAB 대상 TAB 문장 */
    private static List<Case> loadCases() {
        try (InputStream in =
                CommunityTextGuardTest.class.getResourceAsStream("/banned-words/test-cases.txt")) {
            assertThat(in).as("test-cases.txt 리소스").isNotNull();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<Case> cases = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 3);
                cases.add(
                        new Case(
                                TextVerdict.valueOf(parts[0]),
                                TextTarget.valueOf(parts[1]),
                                parts[2]));
            }
            return cases;
        } catch (IOException e) {
            throw new AppException(ErrorType.COMMUNITY_TEXT_RULE_LOAD_FAILED, "test-cases.txt", e);
        }
    }

    static Stream<Arguments> referenceCases() {
        return loadCases().stream().map(c -> Arguments.of(c));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    @DisplayName("기준 구현의 테스트 문장 68개가 모두 같은 판정을 받는다")
    void matchesReferenceCases(Case testCase) {
        assertThat(guard.check(testCase.sentence(), testCase.target()))
                .as(testCase.toString())
                .isEqualTo(testCase.expected());
    }

    @Test
    @DisplayName("테스트 문장 파일이 비어 있거나 잘려도 통과해 버리지 않도록 분포를 고정한다")
    void caseDistribution() {
        Map<String, Long> counts =
                loadCases().stream()
                        .collect(
                                Collectors.groupingBy(
                                        c -> c.expected() + "/" + c.target(),
                                        Collectors.counting()));

        assertThat(counts)
                .containsEntry("BLOCK/CONTENT", 20L)
                .containsEntry("BLOCK/NICKNAME", 9L)
                .containsEntry("PASS/CONTENT", 31L)
                .containsEntry("PASS/NICKNAME", 3L)
                .containsEntry("REVIEW/CONTENT", 5L);
    }

    @Test
    @DisplayName("첫가끝 자모로 입력한 우회도 호환 자모와 같게 막는다")
    void conjoiningJamoBypass() {
        String conjoining = "ᄉᄇ"; // ㅅㅂ를 첫가끝 자모로 쓴 것

        assertThat(guard.check(conjoining, TextTarget.CONTENT)).isEqualTo(TextVerdict.BLOCK);
        assertThat(guard.check("ㅅㅂ", TextTarget.CONTENT)).isEqualTo(TextVerdict.BLOCK);
    }

    @Test
    @DisplayName("닉네임은 띄어쓰기로 사칭어를 쪼개도 막는다")
    void nicknameImpersonationWithSpaces() {
        assertThat(guard.check("끼 루", TextTarget.NICKNAME)).isEqualTo(TextVerdict.BLOCK);
        // 사칭어가 토큰 일부에 걸치는 경우는 공백을 무시한 마지막 검사만 잡는다
        assertThat(guard.check("끼 루a", TextTarget.NICKNAME)).isEqualTo(TextVerdict.BLOCK);
        assertThat(guard.check("Ａdmin", TextTarget.NICKNAME)).isEqualTo(TextVerdict.BLOCK);
        assertThat(guard.check("반도체개미", TextTarget.NICKNAME)).isEqualTo(TextVerdict.PASS);
    }

    @Test
    @DisplayName("본문이 BLOCK이면 COMMUNITY_TEXT_BANNED로 거부한다")
    void requireAllowedThrows() {
        assertThatThrownBy(() -> guard.requireAllowed("이 종목 산 놈들 다 개새끼"))
                .isInstanceOfSatisfying(
                        AppException.class,
                        e ->
                                assertThat(e.getErrorType())
                                        .isEqualTo(ErrorType.COMMUNITY_TEXT_BANNED));
    }

    @Test
    @DisplayName("REVIEW는 막지 않고 통과시키며 번호는 가린다")
    void reviewPassesButMasks() {
        assertThat(guard.requireAllowed("문의는 010-1234-5678")).isEqualTo("문의는 010-****-****");
    }

    @Test
    @DisplayName("휴대폰·계좌번호처럼 보이는 숫자는 가리고 날짜·금액·주가는 그대로 둔다")
    void masking() {
        assertThat(CommunityTextGuard.maskPersonalNumbers("연락처 010-1234-5678"))
                .isEqualTo("연락처 010-****-****");
        assertThat(CommunityTextGuard.maskPersonalNumbers("01012345678")).isEqualTo("010********");
        assertThat(CommunityTextGuard.maskPersonalNumbers("010 123 4567"))
                .isEqualTo("010 *** ****");
        assertThat(CommunityTextGuard.maskPersonalNumbers("계좌 110-123-456789 로 보내요"))
                .isEqualTo("계좌 ***-***-****** 로 보내요");
        assertThat(CommunityTextGuard.maskPersonalNumbers("1234567890")).isEqualTo("**********");
        assertThat(CommunityTextGuard.maskPersonalNumbers("2026-10-29에 412,000원, 1,000,000주"))
                .isEqualTo("2026-10-29에 412,000원, 1,000,000주");
        assertThat(CommunityTextGuard.maskPersonalNumbers("목표 412000원 거래량 98765"))
                .isEqualTo("목표 412000원 거래량 98765");
    }

    @Test
    @DisplayName("너무 긴 입력은 정규식을 돌리기 전에 거부한다")
    void rejectsTooLong() {
        String tooLong = "가".repeat(CommunityTextGuard.MAX_CHECK_LENGTH + 1);

        assertThatThrownBy(() -> guard.check(tooLong, TextTarget.CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.check(null, TextTarget.CONTENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정규식을 노린 긴 입력도 제한 시간 안에 끝난다(ReDoS)")
    void adversarialInputsFinishQuickly() {
        List<String> inputs =
                List.of(
                        "a".repeat(2_000),
                        "ㅅ ".repeat(1_000),
                        "0".repeat(2_000),
                        "-".repeat(2_000),
                        "1-".repeat(1_000),
                        "open . ".repeat(280),
                        "010 ".repeat(500),
                        "ᄉ".repeat(2_000),
                        " ".repeat(1_990) + "시발");
        for (String input : inputs) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () -> {
                        guard.check(input, TextTarget.CONTENT);
                        guard.check(input, TextTarget.NICKNAME);
                        CommunityTextGuard.maskPersonalNumbers(input);
                    });
        }
    }
}
