package com.app.maria.domain.stockpackage.type;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 고객에게 보이는 패키지 점검 설명 문장은행.
 *
 * <p>설명 문장은 전부 여기에 미리 쓰고 사람이 검수한 것만 쓴다. 서버는 {슬롯} 자리에 계산한 숫자나 종목 이름을 끼워 넣을 뿐이고, AI가 쓴 문장은 고객에게 보이지
 * 않는다. 그래서 투자 권유 표현은 검사로 걸러내는 것이 아니라 구성상 나올 수 없다.
 *
 * <p>문장을 추가·수정할 때 지킬 것(StockPackageSentenceTest가 모두 검사한다):
 *
 * <ul>
 *   <li>사실과 정의만 쓴다. 종목·비중·매매에 대한 권유, 평가(좋다·나쁘다), 전망, 지시 표현을 쓰지 않는다.
 *   <li>문장은 "요."로 끝난다. "~하세요", "~해 보세요" 같은 지시·제안 어미를 쓰지 않는다.
 *   <li>슬롯은 {@link #ALLOWED_SLOTS}에 있는 것만 쓴다.
 * </ul>
 */
public enum StockPackageSentence {
    // ───── 위험등급 가중평균 ─────
    AVG_GRADE_DEFINITION(
            "위험등급은 1등급(매우 높은 위험)부터 6등급(매우 낮은 위험)까지 나뉘어요. 가중평균은 각 종목의 등급에 담은 비중을 곱해서 더한 값이에요."),
    AVG_GRADE_FACT("이 패키지의 위험등급 가중평균은 {avg}등급이에요."),

    // ───── 내 투자성향 범위 ─────
    PROFILE_DEFINITION("투자성향마다 담을 수 있는 위험등급의 범위가 정해져 있어요. 범위를 벗어난 종목은 확인한 뒤에 담을 수 있어요."),
    PROFILE_FACT_NONE("내 투자성향 범위를 벗어난 종목은 없어요."),
    PROFILE_FACT_SOME("내 투자성향 범위를 벗어난 종목은 {count}개예요. 해당 종목은 {names}예요."),
    PROFILE_FACT_UNKNOWN("투자성향 진단이 없거나 만료되어 내 성향 범위는 확인할 수 없어요."),
    PROFILE_REFERENCE_RANGE("내 투자성향에서 허용하는 가장 높은 위험은 {minGrade}등급이에요."),
    PROFILE_REFERENCE_AVG_IN("가중평균 {avg}등급은 이 범위 안에 있어요."),
    PROFILE_REFERENCE_AVG_OUT("가중평균 {avg}등급은 이 범위를 벗어나 있어요."),

    // ───── 연 변동성 ─────
    VOLATILITY_DEFINITION(
            "연 변동성은 가격이 1년 동안 오르내린 폭의 정도를 과거 3년 가격으로 계산한 값이에요. 값이 클수록 가격이 크게 움직였다는 뜻이에요."),
    VOLATILITY_FACT("이 패키지의 연 변동성은 {vol}%예요."),
    VOLATILITY_FACT_UNAVAILABLE("연 변동성은 지금 계산할 수 없어요. 잠시 뒤에 다시 점검할 수 있어요."),
    VOLATILITY_REFERENCE("과거 가격으로 계산한 값이고, 앞으로의 가격이나 수익을 예측하는 값이 아니에요."),

    // ───── 종목 집중도 ─────
    CONCENTRATION_DEFINITION(
            "종목 집중도(HHI)는 종목별 비중을 제곱해서 더한 값으로 0부터 10,000 사이예요. 비중이 몇몇 종목에 몰릴수록 값이 커져요."),
    CONCENTRATION_FACT("이 패키지의 종목 집중도는 {hhi}이에요."),
    CONCENTRATION_REFERENCE_HIGHER("같은 {n}종목의 비중이 모두 똑같으면 {eq}이에요. 이 패키지는 그보다 큰 값이에요."),
    CONCENTRATION_REFERENCE_LOWER("같은 {n}종목의 비중이 모두 똑같으면 {eq}이에요. 이 패키지는 그보다 작은 값이에요."),
    CONCENTRATION_REFERENCE_SAME("같은 {n}종목의 비중이 모두 똑같으면 {eq}이에요. 이 패키지는 그와 같은 값이에요."),
    CONCENTRATION_CONTRIBUTION("집중도에서 종목별로 차지하는 몫은 {shares}예요."),

    // ───── 가장 큰 종목 ─────
    TOP_ITEM_DEFINITION("가장 큰 종목은 패키지 안에서 비중이 가장 큰 종목이에요."),
    TOP_ITEM_FACT("{name}의 비중이 {weight}%로 가장 커요."),

    // ───── 테마 ─────
    THEME_DEFINITION("테마는 종목을 성격에 따라 묶은 분류예요. 한 종목이 여러 테마에 속할 수 있어서 테마별 비중의 합은 100%를 넘을 수 있어요."),
    THEME_FACT("비중의 합이 가장 큰 테마는 {theme}로 {weight}%예요."),
    THEME_FACT_NONE("이 패키지의 종목에는 연결된 테마가 없어요."),

    // ───── 업종 ─────
    SECTOR_DEFINITION("업종은 거래소가 나눈 산업 분류예요. 펀드는 업종이 없어서 '업종 없음'으로 묶어요."),
    SECTOR_FACT("비중의 합이 가장 큰 업종은 {sector}로 {weight}%예요."),

    // ───── 설문 투자성향 ─────
    SURVEY_DEFINITION("설문 투자성향은 투자성향 설문 결과예요. 성향마다 담을 수 있는 위험등급의 범위가 정해져 있어요."),
    SURVEY_FACT("내 설문 투자성향은 {type}이고, 허용하는 가장 높은 위험은 {minGrade}등급이에요."),
    SURVEY_FACT_UNKNOWN("투자성향 진단이 없거나 만료되어 설문 투자성향은 확인할 수 없어요."),

    // ───── 거래에서 본 경향 ─────
    TRADING_DEFINITION("거래에서 본 경향은 RIA 계좌에서 체결된 국내 주식·펀드 주문으로 계산한 참고 정보예요. 공식 투자성향이 아니에요."),
    TRADING_FACT_INSUFFICIENT("체결된 매수가 {minBuy}건보다 적어 거래에서 본 경향은 확인할 수 없어요."),
    TRADING_GRADE_FACT("체결된 매수 금액 기준으로 위험등급 가중평균은 {tradeAvg}등급이에요."),
    TRADING_GRADE_DISTRIBUTION("매수 금액을 등급별로 나누면 {distribution}예요."),
    TRADING_GRADE_FACT_NONE("위험등급이 있는 종목의 매수가 없어 위험등급은 계산할 수 없어요."),
    TRADING_CONCENTRATION_DEFINITION("업종과 테마는 매수 금액이 어디에 몰렸는지 보여주는 분류예요. 펀드는 업종이 없어 제외해요."),
    TRADING_SECTOR_FACT("매수 금액이 가장 큰 업종은 {sector}로 전체의 {share}%예요."),
    TRADING_THEME_FACT(
            "매수 금액이 가장 큰 테마는 {theme}로 전체의 {share}%예요. 한 종목이 여러 테마에 속할 수 있어 테마별 합은 100%를 넘을 수 있어요."),
    TRADING_ACTIVITY_DEFINITION("거래 빈도는 체결된 주문 수로 계산해요. 매도 비율은 체결된 주문 중 매도의 비율이에요."),
    TRADING_ACTIVITY_FACT(
            "체결된 주문은 모두 {orderCount}건이고 월 평균 {perMonth}건이에요. 이 중 매도는 {sellRatio}%예요."),

    // ───── 두 결과의 차이 ─────
    COMPARISON_DEFINITION("설문 투자성향과 거래에서 본 경향을 같은 위험등급 기준으로 놓고 차이를 사실로만 보여줘요."),
    COMPARISON_RANGE_FACT(
            "설문 투자성향이 허용하는 가장 높은 위험은 {minGrade}등급이고, 실제 매수의 위험등급 가중평균은 {tradeAvg}등급이에요."),
    COMPARISON_IN("실제 매수의 가중평균은 설문 투자성향 범위 안에 있어요."),
    COMPARISON_OUT("실제 매수의 가중평균은 설문 투자성향 범위를 벗어나 있어요."),
    COMPARISON_OUT_SHARE("설문 투자성향 범위를 벗어난 등급의 종목에 쓴 매수 금액은 전체의 {share}%예요."),
    COMPARISON_UNAVAILABLE("설문 투자성향이나 거래에서 본 경향 중 한쪽을 확인할 수 없어 비교하지 않아요."),

    // ───── 후보 보기 ─────
    CANDIDATE_COUNT("조건에 맞는 상품은 모두 {count}개예요."),
    CANDIDATE_COUNT_TRUNCATED("조건에 맞는 상품은 모두 {count}개이고, 앞의 {shown}개만 보여줘요."),
    CANDIDATE_EMPTY("조건에 맞는 상품이 없어요."),
    CANDIDATE_SORT_NAME_ASC("종목명 가나다순으로 보여줘요."),
    CANDIDATE_SORT_NAME_DESC("종목명 가나다 역순으로 보여줘요."),
    CANDIDATE_SORT_GRADE_ASC("위험등급 1등급부터 순서대로 보여줘요."),
    CANDIDATE_SORT_GRADE_DESC("위험등급 6등급부터 순서대로 보여줘요."),
    CANDIDATE_SORT_VOLATILITY_ASC("연 변동성이 작은 값부터 보여줘요."),
    CANDIDATE_SORT_VOLATILITY_DESC("연 변동성이 큰 값부터 보여줘요."),
    CANDIDATE_SORT_NOTE("이 순서는 정렬 기준에 따른 것일 뿐 상품을 비교하거나 순위를 매긴 것이 아니에요."),
    CANDIDATE_PROFILE_ONLY("내 투자성향 범위 안의 상품만 보여줘요."),
    CANDIDATE_PROFILE_ALL("내 투자성향 범위 밖의 상품도 함께 보여줘요."),
    CANDIDATE_PROFILE_UNKNOWN("투자성향 진단이 없거나 만료되어 성향 범위를 적용하지 않았어요."),
    CANDIDATE_UNSELECTABLE_NO_GRADE("위험등급이 아직 계산되지 않아 담을 수 없어요."),

    // ───── 말로 조건 찾기 ─────
    CONDITION_PARSED("말씀하신 내용에서 찾은 조건은 다음과 같아요."),
    CONDITION_TYPES("{types} 상품군으로 찾아요."),
    CONDITION_THEMES("{themes} 테마로 찾아요."),
    CONDITION_GRADE("위험등급은 {range} 범위로 찾아요."),
    CONDITION_GRADE_EXACT("위험등급은 {range}으로 찾아요."),
    CONDITION_COUNT("종목 수는 {range} 범위로 정했어요."),
    CONDITION_COUNT_EXACT("종목 수는 {range}으로 정했어요."),
    CONDITION_SORT("정렬 기준은 {sortLabel} 방식이에요."),
    CONDITION_PARTIAL("이해하지 못한 조건은 적용하지 않았어요."),
    CONDITION_APPLY_HINT("이 조건으로 후보를 걸러 볼 수 있어요."),
    CONDITION_NOT_UNDERSTOOD("조건을 이해하지 못했어요. 상품군·테마·위험등급으로 직접 골라 볼 수 있어요."),
    CONDITION_UNAVAILABLE("지금은 말로 조건을 찾을 수 없어요. 상품군·테마·위험등급으로 직접 골라 볼 수 있어요."),
    CONDITION_ADVICE_REFUSED("특정 상품에 대한 투자 권유는 하지 않아요."),
    CONDITION_ADVICE_ALTERNATIVE("대신 상품군·테마·위험등급 조건으로 후보를 걸러 볼 수 있고, 직접 담은 패키지는 점검할 수 있어요.");

    /** 문장에 쓸 수 있는 슬롯 이름. 숫자와 DB에 있는 종목·테마·업종 이름만 들어간다. */
    public static final Set<String> ALLOWED_SLOTS =
            Set.of(
                    "avg",
                    "minGrade",
                    "count",
                    "names",
                    "vol",
                    "hhi",
                    "eq",
                    "n",
                    "shares",
                    "name",
                    "weight",
                    "theme",
                    "sector",
                    "type",
                    "minBuy",
                    "tradeAvg",
                    "distribution",
                    "share",
                    "orderCount",
                    "perMonth",
                    "sellRatio",
                    "shown",
                    "types",
                    "themes",
                    "range",
                    "sortLabel");

    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z]+)}");

    private final String template;

    StockPackageSentence(String template) {
        this.template = template;
    }

    public String template() {
        return template;
    }

    /** 슬롯을 값으로 채운 문장. 채우지 못한 슬롯이 있으면 코드 오류이므로 예외를 던진다. */
    public String render(Map<String, String> slots) {
        Matcher matcher = SLOT.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = slots.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException(name() + " 슬롯 값 없음: " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
