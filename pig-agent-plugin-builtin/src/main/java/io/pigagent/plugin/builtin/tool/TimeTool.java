package io.pigagent.plugin.builtin.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Pure-compute date/time tools: current time in a zone, timezone conversion, and Unix epoch ↔ ISO-8601
 * conversion. No I/O. Failures return the canonical {@code {"error"}} (never throw).
 */
public final class TimeTool {

    private static final String UTC = "UTC";

    @Tool(name = "currentDateTime", readOnly = true,
            description = "Get the current date-time as ISO-8601 for a timezone (IANA id, default UTC).")
    public String currentDateTime(
            @ToolParam(name = "timezone", required = false,
                    description = "IANA timezone id, e.g. UTC or Asia/Shanghai; blank = UTC") String timezone) {
        ZoneId zone;
        try {
            zone = zoneOf(timezone);
        } catch (DateTimeException e) {
            return ToolErrors.message("invalid timezone: " + timezone);
        }
        return ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @Tool(name = "convertTimezone", readOnly = true,
            description = "Convert an ISO-8601 date-time from one IANA timezone to another.")
    public String convertTimezone(
            @ToolParam(name = "datetime", description = "ISO-8601 date-time, e.g. 2026-07-15T08:30:00") String datetime,
            @ToolParam(name = "fromZone", description = "source IANA timezone id") String fromZone,
            @ToolParam(name = "toZone", description = "target IANA timezone id") String toZone) {
        ZoneId from;
        ZoneId to;
        try {
            from = zoneOf(fromZone);
            to = zoneOf(toZone);
        } catch (DateTimeException e) {
            return ToolErrors.message("invalid timezone");
        }
        try {
            ZonedDateTime source = parseFlexible(datetime, from);
            return source.withZoneSameInstant(to).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return ToolErrors.message("invalid datetime: " + datetime);
        }
    }

    @Tool(name = "epochToIso", readOnly = true,
            description = "Convert a Unix epoch to ISO-8601 in a timezone. unit is 'seconds' or 'millis'.")
    public String epochToIso(
            @ToolParam(name = "epoch", description = "epoch value (integer)") long epoch,
            @ToolParam(name = "unit", required = false,
                    description = "'seconds' (default) or 'millis'") String unit,
            @ToolParam(name = "timezone", required = false,
                    description = "IANA timezone id; blank = UTC") String timezone) {
        Instant instant;
        String u = unit == null || unit.isBlank() ? "seconds" : unit.trim().toLowerCase();
        if (u.equals("millis") || u.equals("milliseconds") || u.equals("ms")) {
            instant = Instant.ofEpochMilli(epoch);
        } else if (u.equals("seconds") || u.equals("second") || u.equals("s")) {
            instant = Instant.ofEpochSecond(epoch);
        } else {
            return ToolErrors.message("invalid unit (use 'seconds' or 'millis'): " + unit);
        }
        try {
            return instant.atZone(zoneOf(timezone)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeException e) {
            return ToolErrors.message("invalid timezone: " + timezone);
        }
    }

    @Tool(name = "isoToEpoch", readOnly = true,
            description = "Convert an ISO-8601 date-time to Unix epoch seconds and milliseconds (JSON).")
    public String isoToEpoch(
            @ToolParam(name = "datetime",
                    description = "ISO-8601 date-time (offset optional; naive assumed UTC)") String datetime) {
        try {
            Instant instant = parseFlexible(datetime, ZoneId.of(UTC)).toInstant();
            return "{\"epochSeconds\":" + instant.getEpochSecond()
                    + ",\"epochMillis\":" + instant.toEpochMilli() + "}";
        } catch (DateTimeParseException e) {
            return ToolErrors.message("invalid datetime: " + datetime);
        }
    }

    private static ZoneId zoneOf(String timezone) {
        return timezone == null || timezone.isBlank() ? ZoneId.of(UTC) : ZoneId.of(timezone.trim());
    }

    /** Parse an ISO date-time that may or may not carry an offset; a naive value uses {@code fallback}. */
    private static ZonedDateTime parseFlexible(String datetime, ZoneId fallback) {
        String value = datetime == null ? "" : datetime.trim();
        try {
            return ZonedDateTime.parse(value);
        } catch (DateTimeParseException ignore) {
            return java.time.LocalDateTime.parse(value).atZone(fallback);
        }
    }
}
