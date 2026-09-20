package com.app.maria.domain.settlement.component;

import com.app.maria.domain.settlement.exception.SettlementCalculationException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SettlementBusinessDayCalculator {
    private static final int SETTLEMENT_BUSINESS_DAYS = 2;

    private final SettlementHolidayCalendar holidayCalendar;

    public LocalDateTime calculateFinalAt(LocalDateTime provisionalAt) {
        if (provisionalAt == null) {
            throw new SettlementCalculationException("가환전 시각이 필요합니다.");
        }

        LocalDate candidate = provisionalAt.toLocalDate();
        int businessDays = 0;
        while (businessDays < SETTLEMENT_BUSINESS_DAYS) {
            candidate = candidate.plusDays(1);
            if (isBusinessDay(candidate)) {
                businessDays++;
            }
        }
        return candidate.atStartOfDay();
    }

    private boolean isBusinessDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY
                && dayOfWeek != DayOfWeek.SUNDAY
                && !holidayCalendar.isHoliday(date);
    }
}
