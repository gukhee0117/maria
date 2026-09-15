package com.app.maria.domain.settlement.exception;

public class SettlementHolidayCalendarException extends SettlementException {
    public SettlementHolidayCalendarException(String message) {
        super(message);
    }

    public SettlementHolidayCalendarException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
