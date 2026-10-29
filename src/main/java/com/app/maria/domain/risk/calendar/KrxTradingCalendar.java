package com.app.maria.domain.risk.calendar;

import com.app.maria.domain.risk.clock.RiskMarketClock;
import com.app.maria.domain.risk.config.RiskProperties;
import com.app.maria.domain.risk.type.RiskDataStatus;
import com.app.maria.global.exception.AppException;
import com.app.maria.global.exception.ErrorType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

@Component
public class KrxTradingCalendar {
    private final RiskProperties properties;

    public KrxTradingCalendar(RiskProperties properties) {
        this.properties = properties;
    }

    /** Reload per operation: a removed/outdated file must not silently use a stale cache. */
    public Snapshot load() {
        try {
            if (properties.getCalendarPath().isBlank()
                    || !properties.getExpectedCalendarVersion().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException();
            byte[] dates = Files.readAllBytes(Path.of(properties.getCalendarPath()));
            byte[] hours =
                    properties.getTradingHoursPath().isBlank()
                            ? new byte[0]
                            : Files.readAllBytes(Path.of(properties.getTradingHoursPath()));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(dates);
            digest.update((byte) 0);
            digest.update(hours);
            String version = HexFormat.of().formatHex(digest.digest());
            if (!version.equals(properties.getExpectedCalendarVersion()))
                throw new IllegalArgumentException();
            Map<LocalDate, Map<String, String>> overrides = new HashMap<>();
            if (hours.length > 0) {
                for (Map<String, String> row : CalendarCsv.read(hours)) {
                    LocalDate day = LocalDate.parse(row.get("date"));
                    if (row.getOrDefault("source_ref", "").isBlank()
                            || overrides.put(day, row) != null)
                        throw new IllegalArgumentException();
                }
            }
            NavigableMap<LocalDate, Session> sessions = new TreeMap<>();
            LocalDate previous = null;
            for (Map<String, String> row : CalendarCsv.read(dates)) {
                LocalDate day = LocalDate.parse(row.get("date"));
                if (previous != null && !day.equals(previous.plusDays(1)))
                    throw new IllegalArgumentException();
                previous = day;
                String flag = row.get("is_open");
                if (!"0".equals(flag) && !"1".equals(flag)) throw new IllegalArgumentException();
                boolean open = "1".equals(flag);
                Map<String, String> detail = overrides.remove(day);
                boolean override = detail != null;
                if (detail == null) detail = row;
                String closeText = detail.getOrDefault("regular_close_at", "");
                String readyText = detail.getOrDefault("available_at", "");
                if (!open && (override || !closeText.isEmpty() || !readyText.isEmpty()))
                    throw new IllegalArgumentException();
                ZonedDateTime close =
                        closeText.isEmpty()
                                ? null
                                : OffsetDateTime.parse(closeText)
                                        .atZoneSameInstant(RiskMarketClock.ZONE);
                if (close != null
                        && (!close.toLocalDate().equals(day)
                                || detail.getOrDefault("source_ref", "").isBlank()))
                    throw new IllegalArgumentException();
                if (!readyText.isEmpty()) {
                    ZonedDateTime ready =
                            OffsetDateTime.parse(readyText).atZoneSameInstant(RiskMarketClock.ZONE);
                    if (ready.toLocalDate().isBefore(day) || close != null && ready.isBefore(close))
                        throw new IllegalArgumentException();
                }
                sessions.put(day, new Session(open, close));
            }
            if (sessions.isEmpty() || !overrides.isEmpty()) throw new IllegalArgumentException();
            return new Snapshot(sessions, version);
        } catch (Exception e) {
            throw new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE);
        }
    }

    public record Session(boolean open, ZonedDateTime close) {}

    public static final class Snapshot {
        private final NavigableMap<LocalDate, Session> days;
        private final String version;

        private Snapshot(NavigableMap<LocalDate, Session> days, String version) {
            this.days = new TreeMap<>(days);
            this.version = version;
        }

        public String version() {
            return version;
        }

        public boolean isOpen(LocalDate day) {
            if (!days.containsKey(day)) throw new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE);
            return days.get(day).open();
        }

        private LocalDate today(ZonedDateTime now) {
            LocalDate today = now.withZoneSameInstant(RiskMarketClock.ZONE).toLocalDate();
            if (!days.containsKey(today))
                throw new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE);
            return today;
        }

        public LocalDate latest(ZonedDateTime now) {
            LocalDate today = today(now), latest = null;
            Session current = days.get(today);
            if (current.open() && current.close() == null)
                throw new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE);
            for (var entry : days.headMap(today, true).entrySet()) {
                if (entry.getValue().open()
                        && (entry.getKey().isBefore(today)
                                || !entry.getValue().close().isAfter(now))) latest = entry.getKey();
            }
            if (latest == null) throw new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE);
            return latest;
        }

        public ZonedDateTime closeToday(ZonedDateTime now) {
            Session current = days.get(today(now));
            if (!current.open()) return null;
            if (current.close() == null)
                throw new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE);
            return current.close();
        }

        public RiskDataStatus state(LocalDate priceTo, LocalDate referenceDate) {
            if (!isOpen(priceTo) || priceTo.isAfter(referenceDate)) return RiskDataStatus.EXPIRED;
            if (!isOpen(referenceDate)) throw new AppException(ErrorType.RISK_CALENDAR_UNAVAILABLE);
            long age =
                    days.subMap(priceTo, false, referenceDate, true).values().stream()
                            .filter(Session::open)
                            .count();
            return age == 0
                    ? RiskDataStatus.CURRENT
                    : age <= 3 ? RiskDataStatus.STALE : RiskDataStatus.EXPIRED;
        }
    }
}
