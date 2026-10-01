package com.app.maria.global.client.exchange;

import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExchangeApiProperties;
import com.app.maria.global.error.AppException;
import com.app.maria.global.error.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
@Primary
// 기준환율찾기
public class ExchangeRateClient {

    private final RestTemplate restTemplate;
    private final ExchangeApiProperties exchangeApiProperties;
    private final BusinessClockService businessClockService;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // JPY(100), IDR(100)처럼 통화에 따라 100단위 표기가 붙는 경우가 있어(그 단위당 환율),
    // 통화코드와 단위 배수를 함께 뽑아냄
    private static final Pattern CUR_UNIT_PATTERN = Pattern.compile("^([A-Z]+)(?:\\((\\d+)\\))?$");

    // currencyUnit = 화폐 단위
    // 날짜를 안넘겨도 되는 편의용 진입점
    public BigDecimal getBaseRate(String currencyUnit) {
        return getBaseRate(currencyUnit, businessClockService.now().toLocalDate());
    }

    public BigDecimal getBaseRate(String currencyUnit, LocalDate searchDate) {
        String url =
                UriComponentsBuilder.fromHttpUrl(exchangeApiProperties.getUrl())
                        .queryParam("authkey", exchangeApiProperties.getApiKey())
                        .queryParam("searchdate", searchDate.format(DATE_FORMAT))
                        .queryParam("data", "AP01")
                        .toUriString();

        JsonNode response = restTemplate.getForObject(url, JsonNode.class);

        if (response == null || !response.isArray()) {
            throw new AppException(ErrorType.EXCHANGE_RATE_NOT_FOUND);
        }

        // 찾고있는 통화와 일치하는 항목 찾기
        for (JsonNode node : response) {
            Matcher matcher = CUR_UNIT_PATTERN.matcher(node.path("cur_unit").asText());
            if (!matcher.matches() || !currencyUnit.equals(matcher.group(1))) {
                continue;
            }

            // 매매기준율 deal base rate
            String rate = node.path("deal_bas_r").asText().replace(",", "");
            BigDecimal baseRate = new BigDecimal(rate);

            String unitDenomination = matcher.group(2);
            if (unitDenomination != null) {
                // 예: JPY(100)은 "100엔당" 환율이므로, 1단위 환율로 환산
                baseRate =
                        baseRate.divide(new BigDecimal(unitDenomination), 4, RoundingMode.HALF_UP);
            }

            return baseRate;
        }

        // 고시환율 없었던 주말/공휴일 -> 하루 전 날짜 호출해서 최근 영업일 찾음
        // 무한 재귀 방지용
        if (searchDate.isAfter(businessClockService.now().toLocalDate().minusDays(7))) {
            return getBaseRate(currencyUnit, searchDate.minusDays(1));
        }

        throw new AppException(ErrorType.EXCHANGE_RATE_NOT_FOUND, currencyUnit);
    }
}
