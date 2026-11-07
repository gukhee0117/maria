package com.app.maria.global.client.exchange;

import com.app.maria.global.client.securities.SecuritiesMarketClient;
import com.app.maria.global.clock.service.BusinessClockService;
import com.app.maria.global.config.properties.ExchangeApiProperties;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Primary
// 기준환율찾기
public class ExchangeRateClient {

    private final RestTemplate restTemplate;
    private final ExchangeApiProperties exchangeApiProperties;
    private final BusinessClockService businessClockService;
    private final SecuritiesMarketClient securities;

    public ExchangeRateClient(
            RestTemplate restTemplate,
            ExchangeApiProperties exchangeApiProperties,
            BusinessClockService businessClockService) {
        this(restTemplate, exchangeApiProperties, businessClockService, null);
    }

    @Autowired
    public ExchangeRateClient(
            RestTemplate restTemplate,
            ExchangeApiProperties exchangeApiProperties,
            BusinessClockService businessClockService,
            SecuritiesMarketClient securities) {
        this.restTemplate = restTemplate;
        this.exchangeApiProperties = exchangeApiProperties;
        this.businessClockService = businessClockService;
        this.securities = securities;
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // JPY(100), IDR(100)처럼 통화에 따라 100단위 표기가 붙는 경우가 있어(그 단위당 환율),
    // 통화코드와 단위 배수를 함께 뽑아냄
    private static final Pattern CUR_UNIT_PATTERN = Pattern.compile("^([A-Z]+)(?:\\((\\d+)\\))?$");

    // currencyUnit = 화폐 단위
    // 날짜를 안넘겨도 되는 편의용 진입점
    public BigDecimal getBaseRate(String currencyUnit) {
        return fetchWithDate(currencyUnit, businessClockService.now().toLocalDate()).getRate();
    }

    public BigDecimal getBaseRate(String currencyUnit, LocalDate searchDate) {
        return fetchWithDate(currencyUnit, searchDate).getRate();
    }

    /** 확정산용 환율. KIS(수출입은행) 모드는 고시 환율과 같고, 증권사 모드는 증권사가 저장한 확정 환율. */
    public BigDecimal getFinalRate(String currencyUnit, LocalDate searchDate) {
        if (securities != null && securities.isActive()) {
            return securities.finalRate(currencyUnit, searchDate);
        }
        return getBaseRate(currencyUnit, searchDate);
    }

    /** 환율과 실제 조회에 성공한 날짜를 함께 반환한다. */
    public RateWithDate getBaseRateWithDate(String currencyUnit) {
        return fetchWithDate(currencyUnit, businessClockService.now().toLocalDate());
    }

    private RateWithDate fetchWithDate(String currencyUnit, LocalDate searchDate) {
        if (securities != null && securities.isActive()) {
            return securities.baseRate(currencyUnit, searchDate);
        }
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

            return new RateWithDate(baseRate, searchDate);
        }

        // 고시환율 없었던 주말/공휴일 -> 하루 전 날짜 호출해서 최근 영업일 찾음
        // 무한 재귀 방지용
        if (searchDate.isAfter(businessClockService.now().toLocalDate().minusDays(7))) {
            return fetchWithDate(currencyUnit, searchDate.minusDays(1));
        }

        throw new AppException(ErrorType.EXCHANGE_RATE_NOT_FOUND, currencyUnit);
    }
}
