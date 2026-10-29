package com.app.maria.domain.risk;

import com.app.maria.domain.risk.calendar.KrxTradingCalendar;
import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.global.client.risk.PythonGradeResponseDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.springframework.core.io.ClassPathResource;

public final class RiskTestSupport {
    public static RiskProperties properties() throws Exception {
        var properties = new RiskProperties();
        properties.setCalendarPath(new ClassPathResource("risk/calendar.csv").getFile().toString());
        var json =
                new ObjectMapper()
                        .readTree(
                                new ClassPathResource("risk/calendar-parity.json")
                                        .getInputStream());
        properties.setExpectedCalendarVersion(json.get("calendarVersion").asText());
        return properties;
    }

    public static KrxTradingCalendar calendar() throws Exception {
        return new KrxTradingCalendar(properties());
    }

    public static ZonedDateTime at(String text) {
        return ZonedDateTime.parse(text);
    }

    public static RiskMarketClock clock(String text) {
        return new RiskMarketClock(Clock.fixed(at(text).toInstant(), RiskMarketClock.ZONE));
    }

    public static PythonGradeResponseDTO grade(String ticker, String date, long version) {
        return new PythonGradeResponseDTO(
                ticker,
                new BigDecimal("0.3125"),
                2,
                new BigDecimal("0.2410"),
                LocalDate.parse("2023-10-30"),
                LocalDate.parse(date),
                OffsetDateTime.parse(date + "T17:00:00.123456+09:00"),
                version);
    }
}
