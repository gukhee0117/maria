package com.app.maria.domain.settlement.component;

import com.app.maria.domain.settlement.exception.SettlementHolidayCalendarException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SettlementHolidayCalendar {
    private static final String HOLIDAY_RESOURCE = "settlement/korean-holidays.csv";

    private final Map<Integer, Set<LocalDate>> holidaysByYear;

    public SettlementHolidayCalendar() {
        this.holidaysByYear = loadHolidays();
    }

    public boolean isHoliday(LocalDate date) {
        Set<LocalDate> holidays = holidaysByYear.get(date.getYear());
        if (holidays == null) {
            throw new SettlementHolidayCalendarException("정산 휴일 정보가 없습니다. year=" + date.getYear());
        }
        return holidays.contains(date);
    }

    private Map<Integer, Set<LocalDate>> loadHolidays() {
        ClassPathResource resource = new ClassPathResource(HOLIDAY_RESOURCE);
        try (InputStream inputStream = resource.getInputStream();
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            Map<Integer, Set<LocalDate>> result = new HashMap<>();
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                LocalDate holiday = parseHoliday(value, lineNumber);
                result.computeIfAbsent(holiday.getYear(), ignored -> new HashSet<>()).add(holiday);
            }
            if (result.isEmpty()) {
                throw new SettlementHolidayCalendarException("정산 휴일 정보가 비어 있습니다.");
            }
            return result;
        } catch (IOException exception) {
            throw new SettlementHolidayCalendarException("정산 휴일 정보를 읽을 수 없습니다.", exception);
        }
    }

    private LocalDate parseHoliday(String value, int lineNumber) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new SettlementHolidayCalendarException(
                    "정산 휴일 정보 형식이 올바르지 않습니다. line=" + lineNumber, exception);
        }
    }
}
