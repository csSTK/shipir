package dev.bethge.shipir.compose;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

/**
 * Daily time window (UTC) in which an image auto-update may run. {@code start} is inclusive, {@code end} exclusive; a
 * start after the end wraps over midnight (22:00-06:00). A deployment without window ({@code null}) may update at any time.
 */
record ImageUpdateWindow(LocalTime start, LocalTime end) {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    /**
     * @param start {@code HH:mm} or null/blank
     * @param end   {@code HH:mm} or null/blank
     * @return the window, or null when both are absent
     * @throws DeploymentException 400 VALIDATION_ERROR when only one is given, a value is not {@code HH:mm}, or both are equal
     */
    static ImageUpdateWindow parse(String start, String end) {
        boolean noStart = start == null || start.isBlank();
        boolean noEnd = end == null || end.isBlank();
        if (noStart && noEnd) {
            return null;
        }
        if (noStart || noEnd) {
            throw invalid("windowStart and windowEnd must be given together");
        }
        LocalTime s = time(start, "windowStart");
        LocalTime e = time(end, "windowEnd");
        if (s.equals(e)) {
            throw invalid("windowStart and windowEnd must differ");
        }
        return new ImageUpdateWindow(s, e);
    }

    /** True when {@code at} (read in UTC) lies inside the window; a null window is always open. */
    static boolean open(ImageUpdateWindow window, Instant at) {
        if (window == null) {
            return true;
        }
        LocalTime t = at.atOffset(ZoneOffset.UTC).toLocalTime();
        if (window.start.isBefore(window.end)) {
            return !t.isBefore(window.start) && t.isBefore(window.end);
        }
        return !t.isBefore(window.start) || t.isBefore(window.end);
    }

    /** {@code HH:mm} form used in the API. */
    static String format(LocalTime time) {
        return time == null ? null : HH_MM.format(time);
    }

    private static LocalTime time(String value, String field) {
        try {
            return LocalTime.parse(value.trim(), HH_MM);
        } catch (DateTimeParseException e) {
            throw invalid(field + " must be a time of day like 02:00 (UTC)");
        }
    }

    private static DeploymentException invalid(String detail) {
        return new DeploymentException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", List.of(detail));
    }
}
