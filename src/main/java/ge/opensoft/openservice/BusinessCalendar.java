package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.time.*;
import java.util.*;

/**
 * Local business hours evaluated on the timezone timeline, including DST and holiday exclusions.
 */
final class BusinessCalendar {
    private BusinessCalendar() {}

    static Map<String, Object> validate(Object input) {
        var body = object(input);
        require(
                body.keySet().equals(Set.of("zone", "start", "end", "days", "holidays")),
                "Provide zone, start, end, days and holidays");
        try {
            ZoneId.of(WorkJobs.text(body.get("zone"), 80));
            String start = WorkJobs.text(body.get("start"), 5),
                    end = WorkJobs.text(body.get("end"), 5);
            require(
                    start.matches("[0-9]{2}:[0-9]{2}") && end.matches("[0-9]{2}:[0-9]{2}"),
                    "Use HH:mm business hours");
            require(
                    LocalTime.parse(start).isBefore(LocalTime.parse(end)),
                    "Business end must follow start on the same day");
            require(body.get("days") instanceof List<?>, "Days must be a list");
            var days = (List<?>) body.get("days");
            require(!days.isEmpty() && days.size() <= 7, "Choose 1–7 weekdays");
            for (var day : days)
                require(
                        day instanceof Integer n && n >= 1 && n <= 7,
                        "Weekdays must be integers from 1 to 7");
            require(body.get("holidays") instanceof List<?>, "Holidays must be a list");
            var holidays = (List<?>) body.get("holidays");
            require(holidays.size() <= 1000, "Too many holidays");
            for (var holiday : holidays) LocalDate.parse(WorkJobs.text(holiday, 10));
        } catch (DateTimeException invalid) {
            throw new Failure(400, "Invalid calendar date, time or timezone");
        }
        return body;
    }

    static long elapsed(Map<String, Object> calendar, long from, long to) {
        if (to <= from) return 0;
        ZoneId zone = ZoneId.of(calendar.get("zone").toString());
        LocalDate first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate(),
                last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate();
        require(
                java.time.temporal.ChronoUnit.DAYS.between(first, last) <= 3660,
                "Calendar interval exceeds ten years");
        var days = (List<?>) calendar.get("days");
        var holidays = (List<?>) calendar.get("holidays");
        long total = 0;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            if (!days.contains(date.getDayOfWeek().getValue())
                    || holidays.contains(date.toString())) continue;
            long start =
                    date.atTime(LocalTime.parse(calendar.get("start").toString()))
                            .atZone(zone)
                            .toInstant()
                            .toEpochMilli();
            long end =
                    date.atTime(LocalTime.parse(calendar.get("end").toString()))
                            .atZone(zone)
                            .toInstant()
                            .toEpochMilli();
            total += Math.max(0, Math.min(end, to) - Math.max(start, from));
        }
        return total;
    }
}
