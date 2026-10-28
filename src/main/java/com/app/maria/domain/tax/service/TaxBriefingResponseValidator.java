package com.app.maria.domain.tax.service;

import com.app.maria.domain.tax.dto.response.CustomerTaxBriefingResponseDTO;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TaxBriefingResponseValidator {

    private static final Pattern MONEY_PATTERN =
            Pattern.compile("(?<![\\d,])-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?\\s*원");
    private static final Pattern ISO_DATE_PATTERN =
            Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern KOREAN_DATE_PATTERN =
            Pattern.compile("(?:(\\d{4})년\\s*)?(\\d{1,2})월\\s*(\\d{1,2})일");
    private static final Pattern REMAINING_NUMBER_PATTERN = Pattern.compile("\\d");
    private static final Pattern ADVICE_PATTERN =
            Pattern.compile(
                    "(매수|매도|인출|투자|가입).{0,10}(하세요|권장|추천|좋아요|좋습니다|유리해요|유리합니다)"
                            + "|(?:추천|권유)(?:해요|합니다|드려요|드립니다)",
                    Pattern.DOTALL);
    private static final Pattern GUARANTEE_PATTERN =
            Pattern.compile("무조건|보장(?:돼요|됩니다|해요|합니다)|확정\\s*세액");

    public boolean isValid(String explanation, CustomerTaxBriefingResponseDTO briefing) {
        if (explanation == null || explanation.isBlank() || briefing == null) {
            return false;
        }
        if (ADVICE_PATTERN.matcher(explanation).find()
                || GUARANTEE_PATTERN.matcher(explanation).find()) {
            return false;
        }

        String remaining = validateAndRemoveDates(explanation, expectedDate(briefing));
        if (remaining == null) {
            return false;
        }

        remaining = validateAndRemoveAmounts(remaining, allowedAmounts(briefing));
        return remaining != null && !REMAINING_NUMBER_PATTERN.matcher(remaining).find();
    }

    private String validateAndRemoveDates(String text, LocalDate allowedDate) {
        String withoutIsoDates = validateAndRemoveIsoDates(text, allowedDate);
        if (withoutIsoDates == null) {
            return null;
        }
        return validateAndRemoveKoreanDates(withoutIsoDates, allowedDate);
    }

    private String validateAndRemoveIsoDates(String text, LocalDate allowedDate) {
        Matcher matcher = ISO_DATE_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            LocalDate mentionedDate = toDate(matcher.group(1), matcher.group(2), matcher.group(3));
            if (mentionedDate == null || !mentionedDate.equals(allowedDate)) {
                return null;
            }
            matcher.appendReplacement(result, " ");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String validateAndRemoveKoreanDates(String text, LocalDate allowedDate) {
        Matcher matcher = KOREAN_DATE_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            if (allowedDate == null || !matchesAllowedDate(matcher, allowedDate)) {
                return null;
            }
            matcher.appendReplacement(result, " ");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean matchesAllowedDate(Matcher matcher, LocalDate allowedDate) {
        String year = matcher.group(1);
        if (year != null && Integer.parseInt(year) != allowedDate.getYear()) {
            return false;
        }
        return Integer.parseInt(matcher.group(2)) == allowedDate.getMonthValue()
                && Integer.parseInt(matcher.group(3)) == allowedDate.getDayOfMonth();
    }

    private LocalDate toDate(String year, String month, String day) {
        try {
            return LocalDate.of(
                    Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private String validateAndRemoveAmounts(String text, Set<BigDecimal> allowedAmounts) {
        Matcher matcher = MONEY_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            BigDecimal mentionedAmount = parseAmount(matcher.group());
            if (mentionedAmount == null || !allowedAmounts.contains(normalize(mentionedAmount))) {
                return null;
            }
            matcher.appendReplacement(result, " ");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private BigDecimal parseAmount(String amountText) {
        try {
            return new BigDecimal(amountText.replace(",", "").replace("원", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<BigDecimal> allowedAmounts(CustomerTaxBriefingResponseDTO briefing) {
        Set<BigDecimal> amounts = new HashSet<>();
        if (briefing.getCurrent() != null) {
            add(amounts, briefing.getCurrent().getGeneralTax());
            add(amounts, briefing.getCurrent().getRiaTax());
            add(amounts, briefing.getCurrent().getReliefAmount());
        }
        if (briefing.getExpected() != null) {
            add(amounts, briefing.getExpected().getGeneralTax());
            add(amounts, briefing.getExpected().getRiaTax());
            add(amounts, briefing.getExpected().getReliefAmount());
            add(amounts, briefing.getExpected().getAdditionalReliefAmount());
        }
        return amounts;
    }

    private void add(Set<BigDecimal> amounts, BigDecimal amount) {
        if (amount != null) {
            amounts.add(normalize(amount));
        }
    }

    private BigDecimal normalize(BigDecimal amount) {
        return amount.stripTrailingZeros();
    }

    private LocalDate expectedDate(CustomerTaxBriefingResponseDTO briefing) {
        return briefing.getExpected() == null ? null : briefing.getExpected().getExpectedFinalAt();
    }
}
