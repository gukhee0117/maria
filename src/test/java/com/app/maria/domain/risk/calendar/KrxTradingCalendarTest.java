package com.app.maria.domain.risk.calendar;

import static org.assertj.core.api.Assertions.*;

import com.app.maria.domain.risk.RiskTestSupport;
import com.app.maria.global.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class KrxTradingCalendarTest {
    @TempDir Path temporary;

    @Test
    void matchesAllPythonReferenceDatesBoundariesAndExpiry() throws Exception {
        var expected =
                new ObjectMapper()
                        .readTree(
                                new ClassPathResource("risk/calendar-parity.json")
                                        .getInputStream());
        var calendar = RiskTestSupport.calendar().load();
        assertThat(calendar.version()).isEqualTo(expected.get("calendarVersion").asText());
        for (var row : expected.get("cases")) {
            var now = ZonedDateTime.parse(row.get("now").asText());
            var reference = calendar.latest(now);
            assertThat(reference.toString()).isEqualTo(row.get("referenceDate").asText());
            var close = calendar.closeToday(now);
            assertThat(close != null && !now.isBefore(close.plusMinutes(10)))
                    .isEqualTo(row.get("batchDue").asBoolean());
            assertThat(calendar.state(LocalDate.parse("2026-10-23"), reference).name())
                    .isEqualTo(row.get("state").asText());
            assertThat(calendar.latest(now.withZoneSameInstant(java.time.ZoneOffset.UTC)))
                    .isEqualTo(reference);
        }
    }

    @Test
    void doesNotGuessWhenCalendarOrRequiredHoursAreMissing() throws Exception {
        var properties = RiskTestSupport.properties();
        properties.setCalendarPath(temporary.resolve("missing.csv").toString());
        assertThatThrownBy(() -> new KrxTradingCalendar(properties).load())
                .isInstanceOf(AppException.class);
        var calendar = RiskTestSupport.calendar().load();
        assertThatThrownBy(() -> calendar.latest(RiskTestSupport.at("2027-01-01T17:00:00+09:00")))
                .isInstanceOf(AppException.class);
        var wrongProperties = RiskTestSupport.properties();
        wrongProperties.setExpectedCalendarVersion("0".repeat(64));
        var wrong = new KrxTradingCalendar(wrongProperties);
        assertThatThrownBy(wrong::load).isInstanceOf(AppException.class);
    }
}
